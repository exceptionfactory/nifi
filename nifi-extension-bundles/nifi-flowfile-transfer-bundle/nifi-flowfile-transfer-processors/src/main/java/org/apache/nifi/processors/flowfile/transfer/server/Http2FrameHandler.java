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

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2WindowUpdateFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;
import org.apache.nifi.logging.ComponentLog;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;

/**
 * Channel Handler supporting sending and receiving HTTP/2 Frames
 */
public class Http2FrameHandler extends ChannelDuplexHandler {
    private static final boolean END_STREAM = true;

    private final ComponentLog log;

    private final StreamFrameCallback streamFrameCallback;

    public Http2FrameHandler(final ComponentLog log, final StreamFrameCallback streamFrameCallback) {
        this.log = Objects.requireNonNull(log, "Log required");
        this.streamFrameCallback = Objects.requireNonNull(streamFrameCallback, "Stream Frame Callback required");
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext channelHandlerContext, final Throwable cause) throws Exception {
        super.exceptionCaught(channelHandlerContext, cause);
        log.warn("Frame handling failed", cause);
        channelHandlerContext.close();
    }

    @Override
    public void channelReadComplete(final ChannelHandlerContext channelHandlerContext) {
        channelHandlerContext.flush();
    }

    @Override
    public void channelRead(final ChannelHandlerContext channelHandlerContext, final Object frame) throws Exception {
        if (frame instanceof Http2HeadersFrame headersFrame) {
            channelReadHeaders(channelHandlerContext, headersFrame);
        } else if (frame instanceof Http2DataFrame dataFrame) {
            channelReadData(channelHandlerContext, dataFrame);
        } else {
            super.channelRead(channelHandlerContext, frame);
        }
    }

    private void channelReadHeaders(final ChannelHandlerContext channelHandlerContext, final Http2HeadersFrame headersFrame) {
        final Channel channel = channelHandlerContext.channel();
        final SocketAddress remoteAddress = channel.remoteAddress();

        final Http2Headers headers = headersFrame.headers();
        final CharSequence method = headers.method();
        final Http2FrameStream stream = headersFrame.stream();

        log.debug("Client Address [{}] Stream [{}] HTTP {} HEADERS received", remoteAddress, stream.id(), method);
        if (HttpMethod.POST.asciiName().contentEquals(method)) {
            if (headersFrame.isEndStream()) {
                sendResponse(channelHandlerContext, headersFrame.stream(), HttpResponseStatus.BAD_REQUEST);
            } else {
                final StreamAddress streamAddress = getStreamAddress(channelHandlerContext, headersFrame);
                streamFrameCallback.onHeadersFrame(streamAddress, headersFrame);
            }
        } else {
            sendResponse(channelHandlerContext, headersFrame.stream(), HttpResponseStatus.METHOD_NOT_ALLOWED);
        }
    }

    private void channelReadData(final ChannelHandlerContext channelHandlerContext, final Http2DataFrame dataFrame) {
        final StreamAddress streamAddress = getStreamAddress(channelHandlerContext, dataFrame);
        final Http2FrameStream stream = dataFrame.stream();
        log.debug("Client Address [{}] Stream [{}] DATA bytes received [{}]", streamAddress.address(), stream.id(), dataFrame.content().readableBytes());

        streamFrameCallback.onDataFrame(streamAddress, dataFrame);
        if (dataFrame.isEndStream()) {
            final TransferResponse transferResponse = streamFrameCallback.onEndStream(streamAddress);
            final TransferResponseStatus transferResponseStatus = transferResponse.transferResponseStatus();
            final HttpResponseStatus httpResponseStatus = HttpResponseStatus.valueOf(transferResponseStatus.getStatusCode());
            sendResponse(channelHandlerContext, stream, httpResponseStatus);
        }

        final int initialFlowControlledBytes = dataFrame.initialFlowControlledBytes();
        final DefaultHttp2WindowUpdateFrame updateFrame = new DefaultHttp2WindowUpdateFrame(initialFlowControlledBytes);
        updateFrame.stream(stream);
        channelHandlerContext.write(updateFrame);
    }

    private void sendResponse(final ChannelHandlerContext channelHandlerContext, final Http2FrameStream stream, final HttpResponseStatus responseStatus) {
        final Channel channel = channelHandlerContext.channel();
        final SocketAddress remoteAddress = channel.remoteAddress();
        log.info("Client Address [{}] Stream [{}] HTTP {}", remoteAddress, stream.id(), responseStatus.code());

        final Http2Headers headers = new DefaultHttp2Headers();
        headers.status(responseStatus.codeAsText());
        final Http2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(headers).stream(stream);

        channelHandlerContext.write(headersFrame);

        final Http2DataFrame dataFrame = new DefaultHttp2DataFrame(END_STREAM).stream(stream);
        channelHandlerContext.write(dataFrame);
    }

    private StreamAddress getStreamAddress(final ChannelHandlerContext channelHandlerContext, final Http2StreamFrame streamFrame) {
        final Channel channel = channelHandlerContext.channel();
        final SocketAddress remoteAddress = channel.remoteAddress();

        final StreamAddress streamAddress;

        if (remoteAddress instanceof InetSocketAddress socketAddress) {
            final Http2FrameStream stream = streamFrame.stream();
            final int id = stream.id();
            streamAddress = new StreamAddress(socketAddress, id);
        } else {
            throw new IllegalStateException("Client Address not supported [%s]".formatted(remoteAddress));
        }

        return streamAddress;
    }
}
