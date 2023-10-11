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
package org.apache.nifi.kafka.service;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaFuture;
import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.kafka.service.api.common.PartitionState;
import org.apache.nifi.kafka.service.api.consumer.AutoOffsetReset;
import org.apache.nifi.kafka.service.api.consumer.KafkaConsumerService;
import org.apache.nifi.kafka.service.api.consumer.PollingContext;
import org.apache.nifi.kafka.service.api.producer.KafkaProducerService;
import org.apache.nifi.kafka.service.api.producer.ProducerConfiguration;
import org.apache.nifi.kafka.service.api.producer.PublishContext;
import org.apache.nifi.kafka.service.api.record.ByteRecord;
import org.apache.nifi.kafka.service.api.record.KafkaRecord;
import org.apache.nifi.kafka.service.api.record.RecordSummary;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockConfigurationContext;
import org.apache.nifi.util.NoOpProcessor;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Kafka3ConnectionServiceIT {
    private static final String IMAGE_NAME = "confluentinc/cp-kafka:7.3.2";

    private static final String GROUP_ID = Kafka3ConnectionService.class.getSimpleName();

    private static final String TOPIC = Kafka3ConnectionServiceIT.class.getSimpleName();

    private static final String SERVICE_ID = Kafka3ConnectionService.class.getSimpleName();

    private static final String UNREACHABLE_BOOTSTRAP_SERVERS = "127.0.0.1:1000";

    private static final String UNREACHABLE_TIMEOUT = "1 s";

    private static final String TEST_RECORD_VALUE = "value-" + System.currentTimeMillis();

    private static final byte[] RECORD_KEY = new byte[]{1};

    private static final byte[] RECORD_VALUE = TEST_RECORD_VALUE.getBytes(StandardCharsets.UTF_8);

    private static final int POLLING_ATTEMPTS = 3;

    private static KafkaContainer kafkaContainer;

    TestRunner runner;

    Kafka3ConnectionService service;

    @BeforeAll
    static void startContainer() throws ExecutionException, InterruptedException, TimeoutException {
        kafkaContainer = new KafkaContainer(DockerImageName.parse(IMAGE_NAME));
        kafkaContainer.start();

        final Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        try (final Admin adminClient = Admin.create(properties)) {
            final int numPartitions = 1;
            final short replicationFactor = 1;
            final NewTopic newTopic = new NewTopic(TOPIC, numPartitions, replicationFactor);
            final CreateTopicsResult topics = adminClient.createTopics(Collections.singleton(newTopic));
            final KafkaFuture<Void> topicFuture = topics.values().get(TOPIC);
            topicFuture.get(2, TimeUnit.SECONDS);
        }
    }

    @AfterAll
    static void stopContainer() {
        kafkaContainer.stop();
    }

    @BeforeEach
    void setRunner() throws InitializationException {
        runner = TestRunners.newTestRunner(NoOpProcessor.class);
        service = new Kafka3ConnectionService();
        runner.addControllerService(SERVICE_ID, service);
        runner.setProperty(service, Kafka3ConnectionService.BOOTSTRAP_SERVERS, kafkaContainer.getBootstrapServers());
    }

    @Test
    void testProduceOneNoTransaction() {
        runner.enableControllerService(service);
        final ProducerConfiguration producerConfiguration = new ProducerConfiguration(false, null);
        final KafkaProducerService producerService = service.getProducerService(producerConfiguration);
        final KafkaRecord kafkaRecord = new KafkaRecord(null, null, null, null, RECORD_VALUE, Collections.emptyList());
        final List<KafkaRecord> kafkaRecords = Collections.singletonList(kafkaRecord);
        final RecordSummary summary = producerService.send(kafkaRecords.iterator(), new PublishContext(TOPIC + "-produce", null, null));
        assertNotNull(summary);
    }

    @Test
    void testProduceOneWithTransaction() {
        runner.enableControllerService(service);
        final ProducerConfiguration producerConfiguration = new ProducerConfiguration(true, "transaction-");
        final KafkaProducerService producerService = service.getProducerService(producerConfiguration);
        final KafkaRecord kafkaRecord = new KafkaRecord(null, null, null, null, RECORD_VALUE, Collections.emptyList());
        final List<KafkaRecord> kafkaRecords = Collections.singletonList(kafkaRecord);
        final RecordSummary summary = producerService.send(kafkaRecords.iterator(), new PublishContext(TOPIC + "-produce", null, null));
        assertNotNull(summary);
    }

    @Test
    void testProduceConsumeRecord() throws Exception {
        runner.enableControllerService(service);

        final ProducerConfiguration producerConfiguration = new ProducerConfiguration(false, null);
        final KafkaProducerService producerService = service.getProducerService(producerConfiguration);

        final long timestamp = System.currentTimeMillis();
        final KafkaRecord kafkaRecord = new KafkaRecord(null, null, timestamp, RECORD_KEY, RECORD_VALUE, Collections.emptyList());
        final List<KafkaRecord> kafkaRecords = Collections.singletonList(kafkaRecord);
        final RecordSummary summary = producerService.send(kafkaRecords.iterator(), new PublishContext(TOPIC, null, null));
        assertNotNull(summary);

        try (KafkaConsumerService consumerService = service.getConsumerService(null)) {
            final PollingContext pollingContext = new PollingContext(GROUP_ID, Collections.singleton(TOPIC), AutoOffsetReset.EARLIEST);
            final Iterator<ByteRecord> consumerRecords = poll(consumerService, pollingContext);

            assertTrue(consumerRecords.hasNext(), "Consumer Records not found");

            final ByteRecord consumerRecord = consumerRecords.next();
            assertEquals(TOPIC, consumerRecord.getTopic());
            assertEquals(0, consumerRecord.getOffset());
            assertEquals(0, consumerRecord.getPartition());
            assertEquals(timestamp, consumerRecord.getTimestamp());

            final Optional<byte[]> keyFound = consumerRecord.getKey();
            assertTrue(keyFound.isPresent());

            assertArrayEquals(RECORD_KEY, keyFound.get());
            assertArrayEquals(RECORD_VALUE, consumerRecord.getValue());

            assertFalse(consumerRecords.hasNext());
        }
    }

    @Test
    void testVerifySuccessful() {
        final Map<PropertyDescriptor, String> properties = new LinkedHashMap<>();
        properties.put(Kafka3ConnectionService.BOOTSTRAP_SERVERS, kafkaContainer.getBootstrapServers());
        final MockConfigurationContext configurationContext = new MockConfigurationContext(properties, null, null);

        final List<ConfigVerificationResult> results = service.verify(configurationContext, runner.getLogger(), Collections.emptyMap());

        assertFalse(results.isEmpty());

        final ConfigVerificationResult firstResult = results.iterator().next();
        assertEquals(ConfigVerificationResult.Outcome.SUCCESSFUL, firstResult.getOutcome());
        assertNotNull(firstResult.getExplanation());
    }

    @Test
    void testVerifyFailed() {
        final Map<PropertyDescriptor, String> properties = new LinkedHashMap<>();
        properties.put(Kafka3ConnectionService.BOOTSTRAP_SERVERS, UNREACHABLE_BOOTSTRAP_SERVERS);
        properties.put(Kafka3ConnectionService.CLIENT_TIMEOUT, UNREACHABLE_TIMEOUT);

        final MockConfigurationContext configurationContext = new MockConfigurationContext(properties, null, null);

        final List<ConfigVerificationResult> results = service.verify(configurationContext, runner.getLogger(), Collections.emptyMap());

        assertFalse(results.isEmpty());

        final ConfigVerificationResult firstResult = results.iterator().next();
        assertEquals(ConfigVerificationResult.Outcome.FAILED, firstResult.getOutcome());
    }

    @Test
    void testGetProducerService() {
        runner.setProperty(service, Kafka3ConnectionService.BOOTSTRAP_SERVERS, kafkaContainer.getBootstrapServers());
        runner.enableControllerService(service);

        final ProducerConfiguration producerConfiguration = new ProducerConfiguration(false, null);
        final KafkaProducerService producerService = service.getProducerService(producerConfiguration);
        final List<PartitionState> partitionStates = producerService.getPartitionStates(TOPIC);
        assertPartitionStatesFound(partitionStates);
    }

    @Test
    void testGetConsumerService() {
        runner.setProperty(service, Kafka3ConnectionService.BOOTSTRAP_SERVERS, kafkaContainer.getBootstrapServers());
        runner.enableControllerService(service);

        final KafkaConsumerService consumerService = service.getConsumerService(null);

        final PollingContext pollingContext = new PollingContext(GROUP_ID, Collections.singleton(TOPIC), AutoOffsetReset.EARLIEST);

        final List<PartitionState> partitionStates = consumerService.getPartitionStates(pollingContext);
        assertPartitionStatesFound(partitionStates);
    }

    private void assertPartitionStatesFound(final List<PartitionState> partitionStates) {
        assertEquals(1, partitionStates.size());
        final PartitionState partitionState = partitionStates.iterator().next();
        assertEquals(TOPIC, partitionState.getTopic());
        assertEquals(0, partitionState.getPartition());
    }

    private Iterator<ByteRecord> poll(final KafkaConsumerService consumerService, final PollingContext pollingContext) {
        Iterator<ByteRecord> consumerRecords = Collections.emptyIterator();

        for (int i = 0; i < POLLING_ATTEMPTS; i++) {
            final Iterable<ByteRecord> records = consumerService.poll(pollingContext);
            assertNotNull(records);
            consumerRecords = records.iterator();
            if (consumerRecords.hasNext()) {
                break;
            }
        }

        return consumerRecords;
    }
}
