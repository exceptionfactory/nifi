/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.flowfile.transfer.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslHandler;
import org.apache.nifi.logging.ComponentLog;

import javax.net.ssl.SSLEngine;
import java.util.Objects;

/**
 * HTTP Protocol Negotiation Handler configures Channel Pipeline based on HTTP/2
 */
public class HttpProtocolNegotiationHandler extends ApplicationProtocolNegotiationHandler {
    /** Set HTTP/2 as the default Application Protocol when not provided during TLS negotiation */
    private static final String DEFAULT_APPLICATION_PROTOCOL = ApplicationProtocolNames.HTTP_2;

    private final ComponentLog log;

    private final StreamFrameCallback streamFrameCallback;

    /**
     * HTTP Protocol Negotiation Handler defaults to HTTP/1.1 when clients do not indicate supported Application Protocols
     *
     * @param log Component Log
     * @param streamFrameCallback Stream Frame Callback
     */
    public HttpProtocolNegotiationHandler(final ComponentLog log, final StreamFrameCallback streamFrameCallback) {
        super(DEFAULT_APPLICATION_PROTOCOL);
        this.log = Objects.requireNonNull(log);
        this.streamFrameCallback = Objects.requireNonNull(streamFrameCallback);
    }

    /**
     * Configure Channel Pipeline based on negotiated Application Layer Protocol
     *
     * @param channelHandlerContext Channel Handler Context
     * @param protocol Negotiated Protocol ignored in favor of SSLEngine.getApplicationProtocol()
     */
    @Override
    protected void configurePipeline(final ChannelHandlerContext channelHandlerContext, final String protocol) {
        final ChannelPipeline pipeline = channelHandlerContext.pipeline();
        final String applicationProtocol = getApplicationProtocol(channelHandlerContext);

        log.debug("Remote Address [{}] Negotiated Application Protocol [{}]", channelHandlerContext.channel().remoteAddress(), applicationProtocol);

        if (ApplicationProtocolNames.HTTP_2.equals(applicationProtocol)) {
            final Http2FrameCodec http2FrameCodec = Http2FrameCodecBuilder.forServer().build();
            pipeline.addLast(http2FrameCodec);
            pipeline.addLast(new Http2FrameHandler(log, streamFrameCallback));
        } else {
            throw new IllegalStateException(String.format("Application Protocol [%s] not supported", applicationProtocol));
        }
    }

    /**
     * Get Application Protocol for SSLEngine because Netty SslHandler does not handle standard SSLEngine methods
     *
     * @param channelHandlerContext Channel Handler Context containing SslHandler
     * @return Application Protocol defaults to HTTP/1.1 when not provided
     */
    private String getApplicationProtocol(final ChannelHandlerContext channelHandlerContext) {
        final SslHandler sslHandler = channelHandlerContext.pipeline().get(SslHandler.class);
        final SSLEngine sslEngine = sslHandler.engine();
        final String negotiatedApplicationProtocol = sslEngine.getApplicationProtocol();

        final String applicationProtocol;
        if (negotiatedApplicationProtocol == null || negotiatedApplicationProtocol.isEmpty()) {
            applicationProtocol = DEFAULT_APPLICATION_PROTOCOL;
        } else {
            applicationProtocol = negotiatedApplicationProtocol;
        }
        return applicationProtocol;
    }
}
