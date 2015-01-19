/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.engine;

import akka.actor.*;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import fi.iki.elonen.NanoHTTPD;
import org.apache.http.HttpStatus;
import org.openhim.mediator.engine.messages.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>The request handler actor launched whenever a request is received.
 * Handles the routing based on the configured RoutingTable and handles the response finalization.</p>
 * <p>
 * Messages supported:
 * <ul>
 * <li>FinishRequestMessage</li>
 * <li>ExceptErrorMessage</li>
 * <li>AddOrchestrationToCoreResponseMessage</li>
 * </ul>
 * </p>
 *
 * @see RoutingTable
 */
public class MediatorRequestHandler extends UntypedActor {

    public static final String OPENHIM_MIME_TYPE = "application/json+openhim";

    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    protected ActorRef requestCaller;
    protected CoreResponse response = new CoreResponse();
    protected String coreTransactionID;
    protected boolean async = false;
    //finalizingRequest becomes true as soon as we "respondAndEnd()"
    protected boolean finalizingRequest = false;

    protected final MediatorConfig config;


    public MediatorRequestHandler(MediatorConfig config) {
        this.config = config;

        try {
            if (config.getRegistrationConfig()!=null) {
                response.setUrn(config.getRegistrationConfig().getURN());
            }
        } catch (RegistrationConfig.InvalidRegistrationContentException ex) {
            log.error(ex, "Could not read URN");
            log.warning("'x-mediator-urn' will not be included in the mediator response");
        }
    }


    private void loadRequestBody(NanoHTTPD.IHTTPSession session) {
        if ("POST".equals(session.getMethod().toString()) || "PUT".equals(session.getMethod().toString())) {
            try {
                Map<String, String> files = new HashMap<String, String>();
                session.parseBody(files);
            } catch (NanoHTTPD.ResponseException | IOException e) {
                exceptError(e);
            }
        }
    }

    private void routeToActor(String route, Class<? extends Actor> clazz, NanoHTTPD.IHTTPSession session) {
        loadRequestBody(session);

        ActorRef actor = null;
        try {
            //can we pass the mediator config through?
            if (clazz.getConstructor(MediatorConfig.class) != null) {
                actor = getContext().actorOf(Props.create(clazz, config));
            }
        } catch (NoSuchMethodException | SecurityException ex) {
            //no matter. use default
            actor = getContext().actorOf(Props.create(clazz));
        }

        actor.tell(new NanoIHTTPWrapper(getSelf(), getSelf(), route, session), getSelf());
    }

    private void routeRequest(NanoHTTPD.IHTTPSession session) {
        log.info("Received request: " + session.getMethod() + " " + session.getUri());

        Class<? extends Actor> routeTo = config.getRoutingTable().getActorClassForPath(session.getUri());
        if (routeTo!=null) {
            routeToActor(session.getUri(), routeTo, session);
        } else {
            CoreResponse.Response resp = new CoreResponse.Response();
            resp.setStatus(HttpStatus.SC_NOT_FOUND);
            resp.setBody(session.getUri() + " not found");
            resp.putHeader("Content-Type", "text/plain");
            response.setResponse(resp);
            respondAndEnd(HttpStatus.SC_NOT_FOUND);
        }
    }

    private void enableAsyncProcessing() {
        if (coreTransactionID==null || coreTransactionID.isEmpty()) {
            exceptError(new RuntimeException("Cannot enable asyncronous processing if X-OpenHIM-TransactionID is unknown"));
            return;
        }

        log.info("Accepted async request. Responding to client.");
        async = true;

        //store existing response
        CoreResponse.Response _resp = response.getResponse();

        //respond with 202
        CoreResponse.Response accepted = new CoreResponse.Response();
        accepted.setStatus(HttpStatus.SC_ACCEPTED);
        accepted.setBody("Accepted request");
        response.setResponse(accepted);

        respondToCaller(HttpStatus.SC_ACCEPTED);

        //restore response
        response.setResponse(_resp);
    }

    private void processFinishRequestMessage(FinishRequest msg) {
        if (response.getResponse()==null) {
            CoreResponse.Response resp = new CoreResponse.Response();
            resp.setBody(msg.getResponse());
            if (msg.getResponseMimeType()!=null) {
                resp.putHeader("Content-Type", msg.getResponseMimeType());
            }
            resp.setStatus(msg.getResponseStatus());
            response.setResponse(resp);
        }
        respondAndEnd(msg.getResponseStatus());
    }

    private void exceptError(Throwable t) {
        log.error(t, "Exception while processing request");

        if (response.getResponse()==null) {
            CoreResponse.Response resp = new CoreResponse.Response();
            resp.setBody(t.getMessage());
            resp.putHeader("Content-Type", "text/plain");
            resp.setStatus(HttpStatus.SC_INTERNAL_SERVER_ERROR);
            response.setResponse(resp);
        }
        respondAndEnd(HttpStatus.SC_INTERNAL_SERVER_ERROR);
    }

    private void updateTransactionToCoreAPI() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        MediatorHTTPRequest request = new MediatorHTTPRequest(
                getSelf(),
                getSelf(),
                "core-api-update-transaction",
                "PUT",
                "https",
                config.getCoreHost(),
                config.getCoreAPIPort(),
                "/transactions/" + coreTransactionID,
                response.toJSON(),
                headers,
                null
        );

        log.info("Sending updated transaction (" + coreTransactionID + ") to core");
        ActorSelection coreConnector = getContext().actorSelection(config.userPathFor("core-api-connector"));
        coreConnector.tell(request, getSelf());
    }

    private void processResponseFromCoreAPI(MediatorHTTPResponse response) {
        try {
            log.info("Received response from core - status " + response.getStatusCode());
            log.info(response.getBody());
        } finally {
            endRequest();
        }
    }

    private void respondAndEnd(Integer status) {
        if (finalizingRequest) {
            return;
        }

        finalizingRequest = true;

        if (async) {
            updateTransactionToCoreAPI();
        } else {
            try {
                respondToCaller(status);
            } finally {
                endRequest();
            }
        }
    }

    private void respondToCaller(Integer status) {
        if (requestCaller != null) {
            if (response.getStatus()==null) {
                response.setStatus(response.getDescriptiveStatus());
            }

            NanoHTTPD.Response.IStatus nanoStatus = getIntAsNanoHTTPDStatus(status);
            NanoHTTPD.Response serverResponse = new NanoHTTPD.Response(nanoStatus, OPENHIM_MIME_TYPE, response.toJSON());
            requestCaller.tell(serverResponse, getSelf());
            requestCaller = null;
        } else {
            log.warning("FinishRequestMessage received but request caller is gone");
        }
    }

    private NanoHTTPD.Response.IStatus getIntAsNanoHTTPDStatus(final Integer httpStatus) {
        if (httpStatus==null) {
            //200 by default
            return NanoHTTPD.Response.Status.OK;
        }
        return new NanoHTTPD.Response.IStatus() {
            @Override
            public int getRequestStatus() {
                return httpStatus;
            }

            @Override
            public String getDescription() {
                return Integer.toString(getRequestStatus());
            }
        };
    }

    /**
     * To be called when the request handler is all done
     */
    private void endRequest() {
        getContext().stop(getSelf());
    }

    @Override
    public void onReceive(Object msg) throws Exception {
        if (msg instanceof NanoHTTPD.IHTTPSession) {
            requestCaller = getSender();
            coreTransactionID = ((NanoHTTPD.IHTTPSession) msg).getHeaders().get("X-OpenHIM-TransactionID");
            routeRequest((NanoHTTPD.IHTTPSession) msg);
        } else if (msg instanceof AcceptedAsyncRequest) {
            enableAsyncProcessing();
        } else if (msg instanceof FinishRequest) {
            processFinishRequestMessage((FinishRequest) msg);
        } else if (msg instanceof ExceptError) {
            exceptError(((ExceptError) msg).getError());
        } else if (msg instanceof AddOrchestrationToCoreResponse) {
            if (!finalizingRequest) {
                response.addOrchestration(((AddOrchestrationToCoreResponse) msg).getOrchestration());
            }
        } else if (msg instanceof PutPropertyInCoreResponse) {
            if (!finalizingRequest) {
                response.putProperty(((PutPropertyInCoreResponse) msg).getName(), ((PutPropertyInCoreResponse) msg).getValue());
            }
        } else if (msg instanceof MediatorHTTPResponse) {
            processResponseFromCoreAPI((MediatorHTTPResponse) msg);
        } else {
            unhandled(msg);
        }
    }
}