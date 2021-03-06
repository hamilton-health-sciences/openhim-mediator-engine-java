/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.engine;

import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.testkit.JavaTestKit;
import akka.testkit.TestActorRef;
import org.apache.http.HttpStatus;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openhim.mediator.engine.messages.*;
import org.openhim.mediator.engine.testing.MockHTTPConnector;
import org.openhim.mediator.engine.testing.MockLauncher;
import org.openhim.mediator.engine.testing.TestingUtils;
import scala.concurrent.duration.Duration;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class MediatorRequestHandlerTest {
    /**
     * Mocks a mediator route receiver like someone using the engine will implement in order to receive requests.
     */
    private abstract static class MockRouteActor extends UntypedActor {
        public abstract void executeOnReceive(MediatorHTTPRequest msg);

        @Override
        public void onReceive(Object msg) throws Exception {
            if (msg instanceof MediatorHTTPRequest) {
                executeOnReceive((MediatorHTTPRequest) msg);
            } else {
                fail("MediatorRequestActor should never send any messages to a route other than MediatorHTTPRequest");
            }
        }
    }

    static ActorSystem system;
    MediatorConfig testConfig;

    @BeforeClass
    public static void setup() {
        system = ActorSystem.create();
    }

    @AfterClass
    public static void teardown() {
        JavaTestKit.shutdownActorSystem(system);
        system = null;
    }

    @Before
    public void before() throws IOException {
        testConfig = new MediatorConfig();
        testConfig.setName("request-actor-tests");

        InputStream regInfo = getClass().getClassLoader().getResourceAsStream("test-registration-info.json");
        testConfig.setRegistrationConfig(new RegistrationConfig(regInfo));
    }

    private static class BasicRoutingMock extends MockRouteActor {
        @Override
        public void executeOnReceive(MediatorHTTPRequest msg) {
            assertEquals("http", msg.getScheme());
            assertEquals("GET", msg.getMethod());
            assertEquals("/test", msg.getPath());

            FinishRequest fr = new FinishRequest("basic-routing", "text/plain", HttpStatus.SC_OK);
            msg.getRequestHandler().tell(fr, getSelf());
        }
    }

    @Test
    public void testMessage_BasicRouting() throws Exception {
        new JavaTestKit(system) {{
            RoutingTable table = new RoutingTable();
            table.addRoute("/test", BasicRoutingMock.class);
            testConfig.setRoutingTable(table);

            TestActorRef<MediatorRequestHandler> actor = TestActorRef.create(system, Props.create(MediatorRequestHandler.class, testConfig));
            MediatorHTTPRequest testSession = new MediatorHTTPRequest(actor, getRef(), "/test", "GET", "http", "localhost", 1234, "/test");
            actor.tell(testSession, getRef());

            MediatorHTTPResponse response = expectMsgClass(Duration.create(1, TimeUnit.SECONDS), MediatorHTTPResponse.class);

            String body = response.getBody();
            assertTrue(body.contains("\"body\":\"basic-routing\""));
            assertTrue(body.contains("\"status\":200"));
        }};
    }

    private static class AsyncRoutingMock extends MockRouteActor {
        @Override
        public void executeOnReceive(MediatorHTTPRequest msg) {
            //accepted async
            msg.getRequestHandler().tell(new AcceptedAsyncRequest(), getSelf());

            try {
                //simulate a processing delay
                //Thread.sleep would normally be bad to do in an actor, but should be okay for the unit test
                Thread.sleep(200);
            } catch (InterruptedException e) {}

            //end request
            FinishRequest fr = new FinishRequest("async-routing", "text/plain", HttpStatus.SC_OK);
            msg.getRequestHandler().tell(fr, getSelf());
        }
    }

    private static class MockCoreAPI extends MockHTTPConnector {
        @Override
        public String getResponse() {
            return "Updated";
        }

        @Override
        public Integer getStatus() {
            return 200;
        }

        @Override
        public Map<String, String> getHeaders() {
            return Collections.emptyMap();
        }

        @Override
        public void executeOnReceive(MediatorHTTPRequest req) {
            assertEquals("/transactions/test-async", req.getPath());
            assertEquals("PUT", req.getMethod());
        }
    }

    @Test
    public void testMessage_AsyncRouting() throws Exception {
        new JavaTestKit(system) {{
            //mock core api
            MockLauncher.ActorToLaunch mockCoreAPI = new MockLauncher.ActorToLaunch("core-api-connector", MockCoreAPI.class);
            TestingUtils.launchActors(system, testConfig.getName(), Collections.singletonList(mockCoreAPI));

            //route to mock receiver
            RoutingTable table = new RoutingTable();
            table.addRoute("/test", AsyncRoutingMock.class);
            testConfig.setRoutingTable(table);

            //route request
            TestActorRef<MediatorRequestHandler> actor = TestActorRef.create(system, Props.create(MediatorRequestHandler.class, testConfig));
            assertFalse(actor.underlyingActor().async);

            MediatorHTTPRequest testSession = new MediatorHTTPRequest(
                    actor, getRef(), "/test", "GET", "http", "localhost", 1234, "/test", null, Collections.singletonMap("X-OpenHIM-TransactionID", "test-async"), null
            );
            actor.tell(testSession, getRef());

            //get response
            MediatorHTTPResponse response = expectMsgClass(Duration.create(1, TimeUnit.SECONDS), MediatorHTTPResponse.class);
            assertTrue("Async should be enabled", actor.underlyingActor().async);
            assertEquals("Core transaction ID should be set", "test-async", actor.underlyingActor().coreTransactionID);

            //request handler should respond with 202 Accepted
            String body = response.getBody();
            assertTrue(body.contains("\"status\":202"));

            CoreResponse coreResponse = actor.underlyingActor().response;
            assertNotNull(actor.underlyingActor().response);

            //delay a bit waiting for the async update
            expectNoMsg(Duration.create(1, TimeUnit.SECONDS));

            //response token should be updated with final result
            assertNotNull(coreResponse.getResponse());
            assertEquals(new Integer(200), coreResponse.getResponse().getStatus());
            assertEquals("async-routing", coreResponse.getResponse().getBody());

            TestingUtils.clearRootContext(system, testConfig.getName());
        }};
    }

    private static class ErrorRoutingMock extends MockRouteActor {
        public static class TestException extends Exception {
            public TestException() {
                super("test-exception (this is expected)");
            }
        }

        @Override
        public void executeOnReceive(MediatorHTTPRequest msg) {
            try {
                throw new TestException();
            } catch (TestException ex) {
                msg.getRequestHandler().tell(new ExceptError(ex), getSelf());
            }
        }
    }

    @Test
    public void testMessage_ExceptError() throws Exception {
        new JavaTestKit(system) {{
            RoutingTable table = new RoutingTable();
            table.addRoute("/test", ErrorRoutingMock.class);
            testConfig.setRoutingTable(table);

            TestActorRef<MediatorRequestHandler> actor = TestActorRef.create(system, Props.create(MediatorRequestHandler.class, testConfig));
            MediatorHTTPRequest testSession = new MediatorHTTPRequest(actor, getRef(), "/test", "GET", "http", "localhost", 1234, "/test");
            actor.tell(testSession, getRef());

            MediatorHTTPResponse response = expectMsgClass(Duration.create(1, TimeUnit.SECONDS), MediatorHTTPResponse.class);

            String body = response.getBody();
            assertTrue("The exception message should be returned in the body", body.contains("\"body\":\"test-exception (this is expected)\""));
            assertTrue("Expect status 500 Internal Server Error", body.contains("\"status\":500"));
        }};
    }

    @Test
    public void testMessage_AddOrchestrationToCoreResponse() throws Exception {
        new JavaTestKit(system) {{
            TestActorRef<MediatorRequestHandler> actor = TestActorRef.create(system, Props.create(MediatorRequestHandler.class, testConfig));

            CoreResponse.Orchestration testOrch = new CoreResponse.Orchestration();
            testOrch.setName("unit-test");

            actor.tell(new AddOrchestrationToCoreResponse(testOrch), getRef());
            expectNoMsg(Duration.create(500, TimeUnit.MILLISECONDS));

            assertNotNull(actor.underlyingActor().response);
            assertNotNull(actor.underlyingActor().response.getOrchestrations());
            assertEquals("unit-test", actor.underlyingActor().response.getOrchestrations().get(0).getName());
        }};
    }

    @Test
    public void testMessage_PutPropertyInCoreResponse() throws Exception {
        new JavaTestKit(system) {{
            TestActorRef<MediatorRequestHandler> actor = TestActorRef.create(system, Props.create(MediatorRequestHandler.class, testConfig));

            actor.tell(new PutPropertyInCoreResponse("test-property", "test-value"), getRef());
            expectNoMsg(Duration.create(500, TimeUnit.MILLISECONDS));

            assertNotNull(actor.underlyingActor().response);
            assertNotNull(actor.underlyingActor().response.getProperties());
            assertEquals("test-value", actor.underlyingActor().response.getProperties().get("test-property"));
        }};
    }
}