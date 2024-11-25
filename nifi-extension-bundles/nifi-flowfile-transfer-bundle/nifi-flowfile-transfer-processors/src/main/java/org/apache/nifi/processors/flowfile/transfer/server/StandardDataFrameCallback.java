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

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2DataFrame;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processors.flowfile.transfer.io.CommitCallback;
import org.apache.nifi.processors.flowfile.transfer.io.FlowFileAttribute;
import org.apache.nifi.processors.flowfile.transfer.io.FlowFileCallback;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

public class StandardDataFrameCallback implements DataFrameCallback, FlowFileCallback {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final BlockingQueue<Http2DataFrame> dataFrames = new SynchronousQueue<>();

    private final StreamAddress streamAddress;

    private final ComponentLog log;

    private final CommitCallback commitCallback;

    public StandardDataFrameCallback(final StreamAddress streamAddress, final ComponentLog log, final CommitCallback commitCallback) {
        this.streamAddress = Objects.requireNonNull(streamAddress, "Stream Address required");
        this.log = Objects.requireNonNull(log, "Log required");
        this.commitCallback = Objects.requireNonNull(commitCallback, "Commit Callback required");
    }

    @Override
    public Map<String, String> getAttributes() {
        final InetSocketAddress socketAddress = streamAddress.address();
        return Map.of(
                FlowFileAttribute.CLIENT_SOCKET_ADDRESS.getName(), socketAddress.getHostString(),
                FlowFileAttribute.CLIENT_SOCKET_PORT.getName(), Integer.toString(socketAddress.getPort())
        );
    }

    @Override
    public void process(final OutputStream outputStream) throws IOException {
        boolean polling = true;
        while (polling) {
            final Http2DataFrame dataFrame = pollDataFrames();

            final ByteBuf content = dataFrame.content();
            if (content.isReadable()) {
                final int readableBytes = content.readableBytes();
                content.readBytes(outputStream, readableBytes);
            }

            if (dataFrame.isEndStream()) {
                polling = false;
            }
            dataFrame.release();
        }
    }

    @Override
    public CommitCallback getCommitCallback() {
        return commitCallback;
    }

    @Override
    public void onDataFrame(final Http2DataFrame dataFrame) {
        try {
            final boolean success = dataFrames.offer(dataFrame, TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (success) {
                log.debug("Client Address [{}] Stream [{}] DATA Frame received", streamAddress.address(), streamAddress.id());
            } else {
                throw new IllegalStateException("Client Address [%s] Stream [%s] DATA Frame rejected".formatted(streamAddress.address(), streamAddress.id()));
            }
        } catch (final InterruptedException e) {
            throw new RuntimeException("Client Address [%s] Stream [%d] DATA Frame timeout exceeded".formatted(streamAddress.address(), streamAddress.id()));
        }
    }

    private Http2DataFrame pollDataFrames() throws IOException {
        try {
            return dataFrames.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            throw new IOException("Remote Address [%s] Stream [%d] DATA Frame timeout exceeded".formatted(streamAddress.address(), streamAddress.id()));
        }
    }
}
