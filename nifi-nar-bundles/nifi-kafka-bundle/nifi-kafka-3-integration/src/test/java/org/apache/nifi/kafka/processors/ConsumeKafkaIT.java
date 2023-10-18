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

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.nifi.kafka.processors.consumer.ProcessingStrategy;
import org.apache.nifi.kafka.service.api.consumer.AutoOffsetReset;
import org.apache.nifi.kafka.shared.attribute.KafkaFlowFileAttribute;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumeKafkaIT extends AbstractConsumeKafkaIT {

    private static final String CONSUMER_GROUP_ID = ConsumeKafkaIT.class.getName();

    private static final String RECORD_VALUE = ProducerRecord.class.getSimpleName();

    private static final int FIRST_PARTITION = 0;

    private static final long FIRST_OFFSET = 0;

    private TestRunner runner;

    @BeforeEach
    void setRunner() throws InitializationException {
        runner = TestRunners.newTestRunner(ConsumeKafka.class);
        addConnectionService(runner);

        runner.setProperty(ConsumeKafka.CONNECTION_SERVICE, CONNECTION_SERVICE_ID);
        runner.setProperty(ConsumeKafka.GROUP_ID, CONSUMER_GROUP_ID);
    }

    @Test
    void testProcessingStrategyFlowFileNoRecords() {
        runner.setProperty(ConsumeKafka.TOPIC_NAME, UUID.randomUUID().toString());
        runner.setProperty(ConsumeKafka.PROCESSING_STRATEGY, ProcessingStrategy.FLOW_FILE.getValue());

        runner.run();

        runner.assertAllFlowFilesTransferred(ConsumeKafka.SUCCESS, 0);
    }

    @Test
    void testProcessingStrategyFlowFileOneRecord() throws InterruptedException, ExecutionException {
        final String topicName = UUID.randomUUID().toString();
        runner.setProperty(ConsumeKafka.TOPIC_NAME, topicName);
        runner.setProperty(ConsumeKafka.PROCESSING_STRATEGY, ProcessingStrategy.FLOW_FILE.getValue());
        runner.setProperty(ConsumeKafka.AUTO_OFFSET_RESET, AutoOffsetReset.EARLIEST.getValue());

        runner.run(1, false, true);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(getProducerProperties())) {
            final ProducerRecord<String, String> record = new ProducerRecord<>(topicName, null, RECORD_VALUE);
            final Future<RecordMetadata> produced = producer.send(record);
            final RecordMetadata metadata = produced.get();
            assertEquals(topicName, metadata.topic());
        }

        runner.run(1, false, false);
        runner.run(1, true, false);

        final Iterator<MockFlowFile> flowFiles = runner.getFlowFilesForRelationship(ConsumeKafka.SUCCESS).iterator();
        assertTrue(flowFiles.hasNext());

        final MockFlowFile flowFile = flowFiles.next();
        flowFile.assertContentEquals(RECORD_VALUE);
        flowFile.assertAttributeEquals(KafkaFlowFileAttribute.KAFKA_TOPIC, topicName);
        flowFile.assertAttributeEquals(KafkaFlowFileAttribute.KAFKA_PARTITION, Integer.toString(FIRST_PARTITION));
        flowFile.assertAttributeEquals(KafkaFlowFileAttribute.KAFKA_OFFSET, Long.toString(FIRST_OFFSET));
        flowFile.assertAttributeExists(KafkaFlowFileAttribute.KAFKA_TIMESTAMP);
    }
}
