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

package org.apache.nifi.cluster.protocol.jaxb.message;

import org.apache.nifi.cluster.coordination.node.DisconnectionCode;
import org.apache.nifi.cluster.coordination.node.NodeConnectionStatus;
import org.apache.nifi.cluster.coordination.node.NodeWorkload;
import org.apache.nifi.cluster.protocol.ComponentRevision;
import org.apache.nifi.cluster.protocol.ComponentRevisionSnapshot;
import org.apache.nifi.cluster.protocol.ConnectionResponse;
import org.apache.nifi.cluster.protocol.DataFlow;
import org.apache.nifi.cluster.protocol.Heartbeat;
import org.apache.nifi.cluster.protocol.HeartbeatPayload;
import org.apache.nifi.cluster.protocol.NodeIdentifier;
import org.apache.nifi.cluster.protocol.StandardDataFlow;
import org.apache.nifi.cluster.protocol.message.ClusterWorkloadRequestMessage;
import org.apache.nifi.cluster.protocol.message.ClusterWorkloadResponseMessage;
import org.apache.nifi.cluster.protocol.message.ConnectionResponseMessage;
import org.apache.nifi.cluster.protocol.message.HeartbeatMessage;
import org.apache.nifi.cluster.protocol.message.NodeConnectionStatusRequestMessage;
import org.apache.nifi.cluster.protocol.message.NodeConnectionStatusResponseMessage;
import org.apache.nifi.web.Revision;
import org.junit.jupiter.api.Test;

import jakarta.xml.bind.JAXBException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestJaxbProtocolUtils {

    @Test
    public void testRoundTripConnectionResponse() throws JAXBException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();

        final ConnectionResponseMessage msg = new ConnectionResponseMessage();
        final NodeIdentifier nodeId = new NodeIdentifier("id", "localhost", 8000, "localhost", 8001, "localhost", 8002, 8003, true);
        final DataFlow dataFlow = new StandardDataFlow(new byte[0], new byte[0], new byte[0], new HashSet<>());
        final List<NodeConnectionStatus> nodeStatuses = Collections.singletonList(new NodeConnectionStatus(nodeId, DisconnectionCode.NOT_YET_CONNECTED));
        final List<ComponentRevision> componentRevisions = Collections.singletonList(ComponentRevision.fromRevision(new Revision(8L, "client-1", "component-1")));
        final ComponentRevisionSnapshot revisionSnapshot = new ComponentRevisionSnapshot();
        revisionSnapshot.setRevisionUpdateCount(12L);
        revisionSnapshot.setComponentRevisions(componentRevisions);

        final ComponentRevisionSnapshot componentRevisionSnapshot = new ComponentRevisionSnapshot();
        componentRevisionSnapshot.setComponentRevisions(componentRevisions);
        componentRevisionSnapshot.setRevisionUpdateCount(12L);

        msg.setConnectionResponse(new ConnectionResponse(nodeId, dataFlow, "instance-1", nodeStatuses, componentRevisionSnapshot));

        JaxbProtocolUtils.JAXB_CONTEXT.createMarshaller().marshal(msg, baos);
        final Object unmarshalled = JaxbProtocolUtils.JAXB_CONTEXT.createUnmarshaller().unmarshal(new ByteArrayInputStream(baos.toByteArray()));
        assertInstanceOf(ConnectionResponseMessage.class, unmarshalled);

        final ComponentRevisionSnapshot receivedSnapshot = msg.getConnectionResponse().getComponentRevisions();
        final List<ComponentRevision> revisions = receivedSnapshot.getComponentRevisions();
        assertEquals(1, revisions.size());
        assertEquals(8L, revisions.get(0).getVersion().longValue());
        assertEquals("client-1", revisions.get(0).getClientId());
        assertEquals("component-1", revisions.get(0).getComponentId());

        assertEquals(revisionSnapshot.getComponentRevisions(), receivedSnapshot.getComponentRevisions());
        assertEquals(revisionSnapshot.getRevisionUpdateCount(), receivedSnapshot.getRevisionUpdateCount());
    }

    @Test
    public void testRoundTripConnectionStatusRequest() throws JAXBException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();

        final NodeConnectionStatusRequestMessage msg = new NodeConnectionStatusRequestMessage();

        JaxbProtocolUtils.JAXB_CONTEXT.createMarshaller().marshal(msg, baos);
        final Object unmarshalled = JaxbProtocolUtils.JAXB_CONTEXT.createUnmarshaller().unmarshal(new ByteArrayInputStream(baos.toByteArray()));
        assertInstanceOf(NodeConnectionStatusRequestMessage.class, unmarshalled);
    }


    @Test
    public void testRoundTripConnectionStatusResponse() throws JAXBException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();

        final NodeConnectionStatusResponseMessage msg = new NodeConnectionStatusResponseMessage();
        final NodeIdentifier nodeId = new NodeIdentifier("id", "localhost", 8000, "localhost", 8001, "localhost", 8002, 8003, true);
        final NodeConnectionStatus nodeStatus = new NodeConnectionStatus(nodeId, DisconnectionCode.NOT_YET_CONNECTED);
        msg.setNodeConnectionStatus(nodeStatus);

        JaxbProtocolUtils.JAXB_CONTEXT.createMarshaller().marshal(msg, baos);
        final Object unmarshalled = JaxbProtocolUtils.JAXB_CONTEXT.createUnmarshaller().unmarshal(new ByteArrayInputStream(baos.toByteArray()));
        assertInstanceOf(NodeConnectionStatusResponseMessage.class, unmarshalled);
        final NodeConnectionStatusResponseMessage unmarshalledMsg = (NodeConnectionStatusResponseMessage) unmarshalled;

        final NodeConnectionStatus unmarshalledStatus = unmarshalledMsg.getNodeConnectionStatus();
        assertEquals(nodeStatus, unmarshalledStatus);
    }

    @Test
    public void testRoundTripHeartbeat() throws JAXBException {
        final NodeIdentifier nodeId = new NodeIdentifier("id", "localhost", 8000, "localhost", 8001, "localhost", 8002, 8003, true);
        final NodeConnectionStatus nodeStatus = new NodeConnectionStatus(nodeId, DisconnectionCode.NOT_YET_CONNECTED);

        final HeartbeatPayload payload = new HeartbeatPayload();
        payload.setActiveThreadCount(1);
        payload.setSystemStartTime(System.currentTimeMillis());
        payload.setTotalFlowFileBytes(83L);
        payload.setTotalFlowFileCount(4);

        final List<NodeConnectionStatus> clusterStatus = Collections.singletonList(nodeStatus);
        payload.setClusterStatus(clusterStatus);

        final Heartbeat heartbeat = new Heartbeat(nodeId, nodeStatus, payload.marshal());

        final HeartbeatMessage msg = new HeartbeatMessage();
        msg.setHeartbeat(heartbeat);

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JaxbProtocolUtils.JAXB_CONTEXT.createMarshaller().marshal(msg, baos);
        final Object unmarshalled = JaxbProtocolUtils.JAXB_CONTEXT.createUnmarshaller().unmarshal(new ByteArrayInputStream(baos.toByteArray()));
        assertInstanceOf(HeartbeatMessage.class, unmarshalled);
    }

    @Test
    public void testRoundTripClusterWorkloadRequest() throws JAXBException {
        final ClusterWorkloadRequestMessage msg = new ClusterWorkloadRequestMessage();
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JaxbProtocolUtils.JAXB_CONTEXT.createMarshaller().marshal(msg, baos);
        final Object unmarshalled = JaxbProtocolUtils.JAXB_CONTEXT.createUnmarshaller().unmarshal(new ByteArrayInputStream(baos.toByteArray()));
        assertInstanceOf(ClusterWorkloadRequestMessage.class, unmarshalled);
    }

    @Test
    public void testRoundTripClusterWorkloadResponse() throws JAXBException {
        final ClusterWorkloadResponseMessage msg = new ClusterWorkloadResponseMessage();
        final Map<NodeIdentifier, NodeWorkload> expectedNodeWorkloads = new HashMap<>();

        IntStream.range(1, 4).forEach(i -> {
            final String hostname = "node" + i;
            final NodeIdentifier nodeId = new NodeIdentifier(hostname, hostname, 8080, hostname, 8081, hostname, 8082, 8083, false);
            final NodeWorkload workload = new NodeWorkload();
            workload.setReportedTimestamp(System.currentTimeMillis() - 1000);
            workload.setSystemStartTime(System.currentTimeMillis());
            workload.setActiveThreadCount(i);
            workload.setFlowFileCount(i * 10);
            workload.setFlowFileBytes(i * 10 * 1024);
            expectedNodeWorkloads.put(nodeId, workload);
        });
        msg.setNodeWorkloads(expectedNodeWorkloads);

        // Marshall.
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        JaxbProtocolUtils.JAXB_CONTEXT.createMarshaller().marshal(msg, baos);

        // Un-marshall.
        final Object unmarshalled = JaxbProtocolUtils.JAXB_CONTEXT.createUnmarshaller().unmarshal(new ByteArrayInputStream(baos.toByteArray()));
        assertInstanceOf(ClusterWorkloadResponseMessage.class, unmarshalled);

        // Assert result.
        final ClusterWorkloadResponseMessage response = (ClusterWorkloadResponseMessage) unmarshalled;
        assertEquals(expectedNodeWorkloads.size(), response.getNodeWorkloads().size());
        response.getNodeWorkloads().entrySet().stream().forEach(entry -> {
            assertTrue(expectedNodeWorkloads.containsKey(entry.getKey()));
            final NodeWorkload w = entry.getValue();
            NodeWorkload expectedW = expectedNodeWorkloads.get(entry.getKey());
            assertEquals(expectedW.getActiveThreadCount(), w.getActiveThreadCount());
            assertEquals(expectedW.getReportedTimestamp(), w.getReportedTimestamp());
            assertEquals(expectedW.getSystemStartTime(), w.getSystemStartTime());
            assertEquals(expectedW.getFlowFileBytes(), w.getFlowFileBytes());
            assertEquals(expectedW.getFlowFileCount(), w.getFlowFileCount());
        });
    }
}
