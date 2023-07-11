/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.proxy.filter;

import org.apache.kafka.common.message.RequestHeaderData;
import org.apache.kafka.common.message.ResponseHeaderData;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.ApiMessage;

import io.kroxylicious.proxy.config.BaseConfig;

public class FixedClientIdFilter implements RequestFilter, ResponseFilter {

    private final String clientId;

    public static class FixedClientIdFilterConfig extends BaseConfig {

        private final String clientId;

        public FixedClientIdFilterConfig(String clientId) {
            this.clientId = clientId;
        }

        public String getClientId() {
            return clientId;
        }
    }

    FixedClientIdFilter(FixedClientIdFilterConfig config) {
        this.clientId = config.getClientId();
    }

    @Override
    public boolean shouldHandleRequest(ApiKeys apiKey, short apiVersion) {
        return true;
    }

    @Override
    public boolean shouldHandleResponse(ApiKeys apiKey, short apiVersion) {
        return true;
    }

    @Override
    public void onRequest(ApiKeys apiKey, RequestHeaderData header, ApiMessage body, KrpcFilterContext filterContext) {
        System.out.println("intercepted " + apiKey + " request, setting header");
        header.setClientId(clientId);
        filterContext.forwardRequest(header, body);
    }

    @Override
    public void onResponse(ApiKeys apiKey, ResponseHeaderData header, ApiMessage body, KrpcFilterContext filterContext) {
        System.out.println("intercepted " + apiKey + " response");
        filterContext.forwardResponse(header, body);
    }
}
