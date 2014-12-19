/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.engine.messages;

import java.util.Map;

public class MediatorHTTPResponse {
    private final MediatorHTTPRequest originalRequest;
    private final String content;
    private final Integer statusCode;
    private final Map<String, String> headers;

    public MediatorHTTPResponse(MediatorHTTPRequest originalRequest, String content, Integer statusCode, Map<String, String> headers) {
        this.originalRequest = originalRequest;
        this.content = content;
        this.statusCode = statusCode;
        this.headers = headers;
    }

    public MediatorHTTPResponse(String content, Integer statusCode, Map<String, String> headers) {
        this(null, content, statusCode, headers);
    }

    public MediatorHTTPRequest getOriginalRequest() {
        return originalRequest;
    }

    public String getContent() {
        return content;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
}
