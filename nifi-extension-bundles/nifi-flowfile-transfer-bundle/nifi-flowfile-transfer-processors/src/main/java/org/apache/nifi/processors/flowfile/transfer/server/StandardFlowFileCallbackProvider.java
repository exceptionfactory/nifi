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

import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processors.flowfile.transfer.io.CommitCallback;
import org.apache.nifi.processors.flowfile.transfer.io.FlowFileCallback;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class StandardFlowFileCallbackProvider implements StreamFrameCallback, Iterator<FlowFileCallback> {
    private final Queue<StreamAddress> streams = new LinkedBlockingQueue<>();

    private final Map<StreamAddress, StandardDataFrameCallback> callbacks = new ConcurrentHashMap<>();

    private final Map<StreamAddress, TransferResponse> responses = new ConcurrentHashMap<>();

    private final ComponentLog log;

    public StandardFlowFileCallbackProvider(final ComponentLog log) {
        this.log = Objects.requireNonNull(log, "Log required");
    }

    @Override
    public boolean hasNext() {
        return !streams.isEmpty();
    }

    @Override
    public FlowFileCallback next() {
        final StreamAddress streamAddress = streams.remove();
        return callbacks.get(streamAddress);
    }

    @Override
    public void onHeadersFrame(final StreamAddress streamAddress, final Http2HeadersFrame headersFrame) {
        final CommitCallback commitCallback = new CommitCallback() {
            @Override
            public void onSuccess() {
                responses.put(streamAddress, new TransferResponse(TransferResponseStatus.SUCCESS));
            }

            @Override
            public void onFailure(final Throwable exception) {
                responses.put(streamAddress, new TransferResponse(TransferResponseStatus.ERROR));
            }
        };

        final StandardDataFrameCallback dataFrameCallback = new StandardDataFrameCallback(streamAddress, log, commitCallback);
        callbacks.put(streamAddress, dataFrameCallback);
        streams.add(streamAddress);
    }

    @Override
    public void onDataFrame(final StreamAddress streamAddress, final Http2DataFrame dataFrame) {
        final StandardDataFrameCallback callback = callbacks.get(streamAddress);
        if (callback == null) {
            log.error("Stream Address [{}] not found", streamAddress);
        } else {
            callback.onDataFrame(dataFrame);
        }
    }

    @Override
    public TransferResponse onEndStream(final StreamAddress streamAddress) {
        final TransferResponse transferResponse;

        final StandardDataFrameCallback callback = callbacks.get(streamAddress);
        if (callback == null) {
            log.error("Stream Address [{}] not found", streamAddress);
            transferResponse = new TransferResponse(TransferResponseStatus.ERROR);
        } else {
            transferResponse = new TransferResponse(TransferResponseStatus.SUCCESS);
        }

        return transferResponse;
    }
}
