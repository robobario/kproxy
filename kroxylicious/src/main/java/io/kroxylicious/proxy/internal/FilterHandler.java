/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.proxy.internal;

import java.util.Objects;

import org.apache.kafka.common.protocol.ApiMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kroxylicious.proxy.filter.KrpcFilter;
import io.kroxylicious.proxy.filter.TurboInvoker;
import io.kroxylicious.proxy.frame.DecodedRequestFrame;
import io.kroxylicious.proxy.frame.DecodedResponseFrame;
import io.kroxylicious.proxy.frame.OpaqueRequestFrame;
import io.kroxylicious.proxy.frame.OpaqueResponseFrame;
import io.kroxylicious.proxy.future.Promise;
import io.kroxylicious.proxy.internal.util.Assertions;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * A {@code ChannelInboundHandler} (for handling requests from downstream)
 * that applies a single {@link KrpcFilter}.
 */
public class FilterHandler
        extends ChannelDuplexHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilterHandler.class);
    private final KrpcFilter filter;
    private final long timeoutMs;
    private final TurboInvoker invoker;

    public FilterHandler(KrpcFilter filter, long timeoutMs) {
        this.filter = Objects.requireNonNull(filter);
        this.invoker = new TurboInvoker(filter);
        this.timeoutMs = Assertions.requireStrictlyPositive(timeoutMs, "timeout");
    }

    String filterDescriptor() {
        return filter.getClass().getSimpleName() + "@" + System.identityHashCode(filter);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof DecodedRequestFrame) {
            DecodedRequestFrame<?> decodedFrame = (DecodedRequestFrame<?>) msg;
            // Guard against invoking the filter unexpectedly
            if (invoker.shouldDeserializeRequestOuter(decodedFrame.apiKey(), decodedFrame.apiVersion())) {
                var filterContext = new DefaultFilterContext(filter, ctx, decodedFrame, promise, timeoutMs);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("{}: Dispatching downstream {} request to filter{}: {}",
                            ctx.channel(), decodedFrame.apiKey(), filterDescriptor(), msg);
                }
                invoker.onRequest(decodedFrame, filterContext);
            }
            else {
                ctx.write(msg, promise);
            }
        }
        else {
            if (!(msg instanceof OpaqueRequestFrame)
                    && msg != Unpooled.EMPTY_BUFFER) {
                // Unpooled.EMPTY_BUFFER is used by KafkaProxyFrontendHandler#closeOnFlush
                // but otherwise we don't expect any other kind of message
                LOGGER.warn("Unexpected message writing to upstream: {}", msg, new IllegalStateException());
            }
            ctx.write(msg, promise);
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof DecodedResponseFrame) {
            DecodedResponseFrame<?> decodedFrame = (DecodedResponseFrame<?>) msg;
            if (decodedFrame instanceof InternalResponseFrame) {
                InternalResponseFrame<?> frame = (InternalResponseFrame<?>) decodedFrame;
                if (frame.isRecipient(filter)) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("{}: Completing {} response for request sent by this filter{}: {}",
                                ctx.channel(), decodedFrame.apiKey(), filterDescriptor(), msg);
                    }
                    Promise<ApiMessage> p = frame.promise();
                    p.tryComplete(decodedFrame.body());
                }
                else {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("{}: Not completing {} response for request sent by another filter {}",
                                ctx.channel(), decodedFrame.apiKey(), frame.recipient());
                    }
                    ctx.fireChannelRead(msg);
                }
            }
            else if (invoker.shouldDeserializeResponseOuter(decodedFrame.apiKey(), decodedFrame.apiVersion())) {
                var filterContext = new DefaultFilterContext(filter, ctx, decodedFrame, null, timeoutMs);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("{}: Dispatching upstream {} response to filter {}: {}",
                            ctx.channel(), decodedFrame.apiKey(), filterDescriptor(), msg);
                }
                invoker.onResponse(decodedFrame, filterContext);
            }
            else {
                ctx.fireChannelRead(msg);
            }
        }
        else {
            if (!(msg instanceof OpaqueResponseFrame)) {
                LOGGER.warn("Unexpected message reading from upstream: {}", msg, new IllegalStateException());
            }
            ctx.fireChannelRead(msg);
        }
    }

}
