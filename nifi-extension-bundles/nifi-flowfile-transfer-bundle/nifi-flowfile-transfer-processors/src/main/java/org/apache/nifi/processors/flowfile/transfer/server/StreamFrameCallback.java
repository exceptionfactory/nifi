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

/**
 * Abstraction for handling HTTP/2 Stream Frames
 */
public interface StreamFrameCallback {

    void onHeadersFrame(StreamAddress streamAddress, Http2HeadersFrame headersFrame);

    void onDataFrame(StreamAddress streamAddress, Http2DataFrame dataFrame);

    TransferResponse onEndStream(StreamAddress streamAddress);
}
