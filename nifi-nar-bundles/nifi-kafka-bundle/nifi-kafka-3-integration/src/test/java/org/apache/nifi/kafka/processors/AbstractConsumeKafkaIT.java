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

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.nifi.kafka.service.Kafka3ConnectionService;
import org.apache.nifi.kafka.service.api.KafkaConnectionService;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.TestRunner;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Properties;

public abstract class AbstractConsumeKafkaIT {

    protected static final String CONNECTION_SERVICE_ID = Kafka3ConnectionService.class.getSimpleName();

    protected static final KafkaContainer kafkaContainer;

    private static final String IMAGE_NAME = "confluentinc/cp-kafka:7.3.2";

    static {
        kafkaContainer = new KafkaContainer(DockerImageName.parse(IMAGE_NAME));
        kafkaContainer.start();
    }

    protected void addConnectionService(final TestRunner runner) throws InitializationException {
        final KafkaConnectionService connectionService = new Kafka3ConnectionService();
        runner.addControllerService(CONNECTION_SERVICE_ID, connectionService);
        runner.setProperty(connectionService, Kafka3ConnectionService.BOOTSTRAP_SERVERS, kafkaContainer.getBootstrapServers());
        runner.enableControllerService(connectionService);
    }

    protected Properties getProducerProperties() {
        final Properties properties = new Properties();
        properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return properties;
    }
}
