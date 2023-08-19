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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.nifi.json.JsonRecordSetWriter;
import org.apache.nifi.json.JsonTreeReader;
import org.apache.nifi.kafka.service.Kafka3ConnectionService;
import org.apache.nifi.kafka.service.api.KafkaConnectionService;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.serialization.RecordSetWriterFactory;
import org.apache.nifi.util.TestRunner;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public abstract class PublishKafkaBaseIT {
    public static final String IMAGE_NAME = "confluentinc/cp-kafka:7.3.2";

    protected static final long TIMESTAMP = System.currentTimeMillis();

    protected static final String SERVICE_ID = Kafka3ConnectionService.class.getSimpleName();

    public static final Duration DURATION_POLL = Duration.ofMillis(1000L);

    protected static final KafkaContainer kafka;

    protected static ObjectMapper objectMapper;

    // https://www.testcontainers.org/test_framework_integration/manual_lifecycle_control/
    static {
        kafka = new KafkaContainer(DockerImageName.parse(IMAGE_NAME));
        kafka.start();
    }

    @BeforeAll
    protected static void beforeAll() {
        objectMapper = new ObjectMapper();
    }

    protected void addKafkaConnectionService(final TestRunner runner) throws InitializationException {
        final Map<String, String> connectionServiceProps = new HashMap<>();
        connectionServiceProps.put(Kafka3ConnectionService.BOOTSTRAP_SERVERS.getName(), kafka.getBootstrapServers());
        final KafkaConnectionService connectionService = new Kafka3ConnectionService();
        runner.addControllerService(SERVICE_ID, connectionService, connectionServiceProps);
        runner.enableControllerService(connectionService);
    }

    protected void addRecordReaderService(final TestRunner runner) throws InitializationException {
        final String readerId = "record-reader";
        final RecordReaderFactory readerService = new JsonTreeReader();
        runner.addControllerService(readerId, readerService);
        runner.enableControllerService(readerService);
        runner.setProperty(readerId, readerId);
    }

    protected void addRecordWriterService(final TestRunner runner) throws InitializationException {
        final String writerId = "record-writer";
        final RecordSetWriterFactory writerService = new JsonRecordSetWriter();
        runner.addControllerService(writerId, writerService);
        runner.enableControllerService(writerService);
        runner.setProperty(writerId, writerId);
    }

    protected void addRecordKeyWriterService(final TestRunner runner) throws InitializationException {
        final String writerId = "record-key-writer";
        final RecordSetWriterFactory writerService = new JsonRecordSetWriter();
        runner.addControllerService(writerId, writerService);
        runner.enableControllerService(writerService);
        runner.setProperty(writerId, writerId);
    }

    protected Properties getKafkaConsumerProperties() {
        final Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "my-group");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, Boolean.TRUE.toString());
        properties.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, "1000");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "15000");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return properties;
    }
}
