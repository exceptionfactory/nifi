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
package org.apache.nifi.processors.flowfile.transfer;

import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.configuration.DefaultSchedule;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.event.transport.EventServer;
import org.apache.nifi.event.transport.EventServerFactory;
import org.apache.nifi.event.transport.netty.NettyEventServerFactory;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractSessionFactoryProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessSessionFactory;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.processors.flowfile.transfer.io.CommitCallback;
import org.apache.nifi.processors.flowfile.transfer.io.FlowFileCallback;
import org.apache.nifi.processors.flowfile.transfer.server.HttpServerFactory;
import org.apache.nifi.processors.flowfile.transfer.server.StandardFlowFileCallbackProvider;
import org.apache.nifi.security.util.ClientAuth;
import org.apache.nifi.ssl.SSLContextService;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
@DefaultSchedule(period = "25 ms")
@Tags({"FlowFile", "Transfer", "HTTP"})
public class ListenFlowFile extends AbstractSessionFactoryProcessor {

    static final PropertyDescriptor ADDRESS = new PropertyDescriptor.Builder()
            .name("Address")
            .displayName("Address")
            .description("Internet Protocol Address on which to listen for FlowFiles. The default value enables listening on all addresses.")
            .required(true)
            .defaultValue("0.0.0.0")
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .build();

    static final PropertyDescriptor PORT = new PropertyDescriptor.Builder()
            .name("Port")
            .displayName("Port")
            .description("TCP port number on which to listen for FlowFiles over HTTP")
            .required(true)
            .defaultValue("8000")
            .addValidator(StandardValidators.PORT_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .build();

    static final PropertyDescriptor SSL_CONTEXT_SERVICE = new PropertyDescriptor.Builder()
            .name("SSL Context Service")
            .displayName("SSL Context Service")
            .description("SSL Context Service enables TLS communication for HTTPS")
            .required(true)
            .identifiesControllerService(SSLContextService.class)
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .build();

    static final PropertyDescriptor CLIENT_AUTHENTICATION = new PropertyDescriptor.Builder()
            .name("Client Authentication")
            .displayName("Client Authentication")
            .description("Client authentication policy for TLS communication with HTTPS")
            .required(true)
            .allowableValues(ClientAuth.values())
            .defaultValue(ClientAuth.WANT.name())
            .expressionLanguageSupported(ExpressionLanguageScope.NONE)
            .build();

    static final Relationship SUCCESS = new Relationship.Builder()
            .name("success")
            .description("Received FlowFiles")
            .build();

    private static final Set<Relationship> RELATIONSHIPS = Collections.singleton(SUCCESS);

    private static final List<PropertyDescriptor> DESCRIPTORS = List.of(
            ADDRESS,
            PORT,
            SSL_CONTEXT_SERVICE,
            CLIENT_AUTHENTICATION
    );

    private static final String THREAD_NAME_PREFIX = "%s[%s]";

    private static final String TRANSIT_URI_FORMAT = "https://%s:%d/flow-files";

    private Iterator<FlowFileCallback> flowFileCallbackProvider;

    private String hostAddress;

    private EventServer server;

    @Override
    public final Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return DESCRIPTORS;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) throws UnknownHostException {
        final EventServerFactory eventServerFactory = createEventServerFactory(context);
        server = eventServerFactory.getEventServer();
    }

    @OnStopped
    public void onStopped() {
        if (server == null) {
            getLogger().info("Server not running");
            server = null;
        } else {
            server.shutdown();
        }
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSessionFactory sessionFactory) throws ProcessException {
        if (flowFileCallbackProvider.hasNext()) {
            final ProcessSession session = sessionFactory.createSession();

            while (flowFileCallbackProvider.hasNext()) {
                final FlowFileCallback flowFileCallback = flowFileCallbackProvider.next();
                onFlowFile(session, flowFileCallback);
            }
        }
    }

    int getPort() {
        return server.getListeningPort();
    }

    private void onFlowFile(final ProcessSession session, final FlowFileCallback flowFileCallback) throws ProcessException {
        final String transitUri = getTransitUri();

        FlowFile flowFile = session.create();
        try {
            flowFile = session.write(flowFile, flowFileCallback);
            flowFile = session.putAllAttributes(flowFile, flowFileCallback.getAttributes());
            session.getProvenanceReporter().receive(flowFile, transitUri);
            session.transfer(flowFile, SUCCESS);

            final CommitCallback commitCallback = flowFileCallback.getCommitCallback();
            session.commitAsync(commitCallback::onSuccess, commitCallback::onFailure);
        } catch (final Exception e) {
            getLogger().warn("FlowFile Transit URI [{}] processing failed {}", transitUri, flowFile, e);
            session.remove(flowFile);
        }
    }

    private String getTransitUri() {
        return String.format(TRANSIT_URI_FORMAT, hostAddress, getPort());
    }

    private EventServerFactory createEventServerFactory(final ProcessContext context) throws UnknownHostException {
        final String address = context.getProperty(ADDRESS).getValue();
        final InetAddress serverAddress = InetAddress.getByName(address);
        hostAddress = serverAddress.getCanonicalHostName();
        final int port = context.getProperty(PORT).asInteger();

        final SSLContextService sslContextService = context.getProperty(SSL_CONTEXT_SERVICE).asControllerService(SSLContextService.class);
        final SSLContext sslContext = sslContextService.createContext();

        final StandardFlowFileCallbackProvider standardFlowFileCallbackProvider = new StandardFlowFileCallbackProvider(getLogger());
        flowFileCallbackProvider = standardFlowFileCallbackProvider;

        final NettyEventServerFactory eventServerFactory = new HttpServerFactory(getLogger(), standardFlowFileCallbackProvider, serverAddress, port, sslContext);
        final int workerThreads = context.getMaxConcurrentTasks();
        eventServerFactory.setWorkerThreads(workerThreads);
        final String threadNamePrefix = THREAD_NAME_PREFIX.formatted(ListenFlowFile.class.getSimpleName(), getIdentifier());
        eventServerFactory.setThreadNamePrefix(threadNamePrefix);

        final ClientAuth clientAuth = ClientAuth.valueOf(context.getProperty(CLIENT_AUTHENTICATION).getValue());
        eventServerFactory.setClientAuth(clientAuth);

        return eventServerFactory;
    }
}
