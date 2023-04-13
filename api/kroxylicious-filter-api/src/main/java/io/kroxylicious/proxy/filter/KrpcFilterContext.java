/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.filter;

import java.util.concurrent.CompletionStage;

import org.apache.kafka.common.protocol.ApiMessage;
import org.apache.kafka.common.utils.ByteBufferOutputStream;

import io.kroxylicious.proxy.ContextualProxyMetrics;

/**
 * A context to allow filters to interact with other filters and the pipeline.
 */
public interface KrpcFilterContext {
    /**
     * A description of this channel.
     * @return A description of this channel (typically used for logging).
     */
    String channelDescriptor();

    /**
     * Create a ByteBufferOutputStream of the given capacity.
     * The backing buffer will be deallocated when the request processing is completed
     * @param initialCapacity The initial capacity of the buffer.
     * @return The allocated ByteBufferOutputStream
     */
    ByteBufferOutputStream createByteBufferOutputStream(int initialCapacity);

    /**
     * The SNI hostname provided by the client, if any.
     * @return the SNI hostname provided by the client.  Will be null if the client is
     * using a non-TLS connection or the TLS client hello didn't provide one.
     */
    String sniHostname();

    /**
     * Send a request towards the broker, invoking upstream filters.
     * @param request The request to forward to the broker.
     */
    void forwardRequest(ApiMessage request);

    /**
     * Send a message from a filter towards the broker, invoking upstream filters
     * and being informed of the response via TODO.
     * The response will pass through upstream filters prior to the handler being invoked.
     * Response propagation will stop once the handler has completed,
     * i.e. the downstream filters will not receive the response.
     *
     * @param apiVersion The version of the request to use
     * @param request The request to send.
     * @param <T> The type of the response
     * @return CompletionStage providing the response.
     */
    <T extends ApiMessage> CompletionStage<T> sendRequest(short apiVersion, ApiMessage request);

    /**
     * Send a response towards the client, invoking downstream filters.
     * @param response The response to forward to the client.
     */
    void forwardResponse(ApiMessage response);

    ContextualProxyMetrics metrics();

    // TODO an API to allow a filter to add/remove another filter from the pipeline
}
