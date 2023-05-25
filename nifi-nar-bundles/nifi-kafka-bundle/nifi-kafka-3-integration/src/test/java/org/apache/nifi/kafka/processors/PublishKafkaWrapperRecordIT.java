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
package org.apache.nifi.kafka.processors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.io.IOUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.nifi.json.JsonRecordSetWriter;
import org.apache.nifi.json.JsonTreeReader;
import org.apache.nifi.kafka.processors.producer.wrapper.WrapperRecord;
import org.apache.nifi.kafka.service.Kafka3ConnectionService;
import org.apache.nifi.kafka.service.api.KafkaConnectionService;
import org.apache.nifi.kafka.shared.property.PublishStrategy;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.serialization.RecordSetWriterFactory;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.MethodName.class)
public class PublishKafkaWrapperRecordIT {
    private static final String IMAGE_NAME = "confluentinc/cp-kafka:7.3.2";

    private static final String TEST_TOPIC = "nifi-" + System.currentTimeMillis();

    private static final String SERVICE_ID = Kafka3ConnectionService.class.getSimpleName();

    private static final String TEST_RESOURCE = "org/apache/nifi/kafka/processors/publish/ffwrapper.json";

    private static final String KEY_ATTRIBUTE_KEY = "keyAttribute";
    private static final String KEY_ATTRIBUTE_VALUE = "keyAttributeValue";

    private static final int TEST_RECORD_COUNT = 3;

    private static KafkaContainer kafka;

    private static byte[] bytesJson;

    private static ObjectMapper objectMapper;

    @BeforeAll
    static void beforeAll() throws IOException {
        kafka = new KafkaContainer(DockerImageName.parse(IMAGE_NAME));
        kafka.start();

        bytesJson = IOUtils.toByteArray(Objects.requireNonNull(
                PublishKafkaWrapperRecordIT.class.getClassLoader().getResource(TEST_RESOURCE)));
        objectMapper = new ObjectMapper();
    }

    @AfterAll
    static void afterAll() {
        kafka.stop();
    }

    @Test
    public void test_1_KafkaTestContainerProduceOneFlowFile() throws InitializationException {
        final TestRunner runner = TestRunners.newTestRunner(PublishKafka.class);
        runner.setValidateExpressionUsage(false);

        final Map<String, String> connectionServiceProps = new HashMap<>();
        connectionServiceProps.put(Kafka3ConnectionService.BOOTSTRAP_SERVERS.getName(), kafka.getBootstrapServers());
        final KafkaConnectionService connectionService = new Kafka3ConnectionService();
        runner.addControllerService(SERVICE_ID, connectionService, connectionServiceProps);
        runner.enableControllerService(connectionService);

        runner.setProperty(PublishKafka.CONNECTION_SERVICE, SERVICE_ID);
        runner.setProperty(PublishKafka.TOPIC_NAME, TEST_TOPIC);
        runner.setProperty(PublishKafka.KEY, KEY_ATTRIBUTE_KEY);
        runner.setProperty(PublishKafka.MESSAGE_KEY_FIELD, "address");
        runner.setProperty(PublishKafka.ATTRIBUTE_NAME_REGEX, "a.*");
        runner.setProperty(PublishKafka.PUBLISH_STRATEGY, PublishStrategy.USE_WRAPPER.name());

        final String readerId = "record-reader";
        final RecordReaderFactory readerService = new JsonTreeReader();
        runner.addControllerService(readerId, readerService);
        runner.enableControllerService(readerService);
        runner.setProperty(readerId, readerId);

        final String writerId = "record-writer";
        final RecordSetWriterFactory writerService = new JsonRecordSetWriter();
        runner.addControllerService(writerId, writerService);
        runner.enableControllerService(writerService);
        runner.setProperty(writerId, writerId);

        final String writerKeyId = "record-key-writer";
        final RecordSetWriterFactory writerKeyService = new JsonRecordSetWriter();
        runner.addControllerService(writerKeyId, writerKeyService);
        runner.enableControllerService(writerKeyService);
        runner.setProperty(writerKeyId, writerKeyId);

        final Map<String, String> attributes = new HashMap<>();
        attributes.put(KEY_ATTRIBUTE_KEY, KEY_ATTRIBUTE_VALUE);
        attributes.put("a1", "valueA1");
        attributes.put("b1", "valueB1");

        runner.enqueue(bytesJson, attributes);
        runner.run(1);
        runner.assertAllFlowFilesTransferred(PublishKafka.REL_SUCCESS, 1);
    }

    @Test
    public void test_2_KafkaTestContainerConsumeMultipleRecords() throws JsonProcessingException {
        final Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "my-group");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.TRUE.toString());
        properties.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "15000");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(Collections.singletonList(TEST_TOPIC));
            final ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
            assertEquals(TEST_RECORD_COUNT, records.count());
            for (ConsumerRecord<String, String> record : records) {
                final ObjectNode kafkaKey = (ObjectNode) objectMapper.readTree(record.key());
                assertNotNull(kafkaKey);
                assertEquals("Main", kafkaKey.get("street-name").textValue());
                assertEquals(5, (kafkaKey.get("street-number").intValue() % 100));
                final ObjectNode kafkaValue = (ObjectNode) objectMapper.readTree(record.value());
                assertNotNull(kafkaValue);

                assertTrue(kafkaValue.get(WrapperRecord.METADATA) instanceof ObjectNode);
                assertTrue(kafkaValue.get(WrapperRecord.HEADERS) instanceof ObjectNode);
                assertTrue(kafkaValue.get(WrapperRecord.KEY) instanceof ObjectNode);
                assertTrue(kafkaValue.get(WrapperRecord.VALUE) instanceof ObjectNode);

                // TODO remove; not relevant for publish strategy wrapper
                final List<Header> headers = Arrays.asList(record.headers().toArray());
                assertEquals(1, headers.size());
                final Header header = record.headers().iterator().next();
                assertEquals("a1", header.key());
                assertEquals("valueA1", new String(header.value(), StandardCharsets.UTF_8));
            }
        }
    }
}
