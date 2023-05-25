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

import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.nifi.kafka.service.Kafka3ConnectionService;
import org.apache.nifi.kafka.service.api.KafkaConnectionService;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@TestMethodOrder(MethodOrderer.MethodName.class)
public class PublishKafkaDelimitedIT {
    private static final String IMAGE_NAME = "confluentinc/cp-kafka:7.3.2";

    private static final String TEST_TOPIC = "nifi-" + System.currentTimeMillis();

    private static final String SERVICE_ID = Kafka3ConnectionService.class.getSimpleName();

    private static final String TEST_RECORD_VALUE = "value-" + System.currentTimeMillis();

    private static final int TEST_RECORD_COUNT = 3;

    private static KafkaContainer kafka;

    @BeforeAll
    static void beforeAll() {
        kafka = new KafkaContainer(DockerImageName.parse(IMAGE_NAME));
        kafka.start();
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
        runner.setProperty(PublishKafka.MESSAGE_DEMARCATOR, "xx");
        runner.setProperty(PublishKafka.ATTRIBUTE_NAME_REGEX, "a.*");

        final Map<String, String> attributes = new HashMap<>();
        attributes.put("a1", "valueA1");
        attributes.put("b1", "valueB1");

        runner.enqueue(StringUtils.repeat(TEST_RECORD_VALUE + "xx", TEST_RECORD_COUNT), attributes);
        runner.run(1);
        runner.assertAllFlowFilesTransferred(PublishKafka.REL_SUCCESS, 1);
    }

    @Test
    public void test_2_KafkaTestContainerConsumeMultipleRecords() {
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
            records.forEach(record -> {
                assertNull(record.key());
                assertEquals(TEST_RECORD_VALUE, record.value());
                final List<Header> headers = Arrays.asList(record.headers().toArray());
                assertEquals(1, headers.size());
                final Header header = record.headers().iterator().next();
                assertEquals("a1", header.key());
                assertEquals("valueA1", new String(header.value(), StandardCharsets.UTF_8));
            });
        }
    }
}
