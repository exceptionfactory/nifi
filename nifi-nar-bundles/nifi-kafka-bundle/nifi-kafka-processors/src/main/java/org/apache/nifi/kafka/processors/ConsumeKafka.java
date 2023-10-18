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

import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.kafka.processors.consumer.ProcessingStrategy;
import org.apache.nifi.kafka.service.api.KafkaConnectionService;
import org.apache.nifi.kafka.service.api.common.OffsetSummary;
import org.apache.nifi.kafka.service.api.common.PartitionState;
import org.apache.nifi.kafka.service.api.common.TopicPartitionSummary;
import org.apache.nifi.kafka.service.api.consumer.AutoOffsetReset;
import org.apache.nifi.kafka.service.api.consumer.ConsumerConfiguration;
import org.apache.nifi.kafka.service.api.consumer.KafkaConsumerService;
import org.apache.nifi.kafka.service.api.consumer.PollingContext;
import org.apache.nifi.kafka.service.api.consumer.PollingSummary;
import org.apache.nifi.kafka.service.api.header.RecordHeader;
import org.apache.nifi.kafka.service.api.record.ByteRecord;
import org.apache.nifi.kafka.shared.attribute.KafkaFlowFileAttribute;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.VerifiableProcessor;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.provenance.ProvenanceReporter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.nifi.expression.ExpressionLanguageScope.NONE;

@InputRequirement(InputRequirement.Requirement.INPUT_FORBIDDEN)
@Tags({"kafka", "consumer", "record"})
public class ConsumeKafka extends AbstractProcessor implements VerifiableProcessor {

    static final PropertyDescriptor CONNECTION_SERVICE = new PropertyDescriptor.Builder()
            .name("Kafka Connection Service")
            .displayName("Kafka Connection Service")
            .description("Provides connections to Kafka Broker for publishing Kafka Records")
            .identifiesControllerService(KafkaConnectionService.class)
            .expressionLanguageSupported(NONE)
            .required(true)
            .build();

    static final PropertyDescriptor GROUP_ID = new PropertyDescriptor.Builder()
            .name("Group ID")
            .displayName("Group ID")
            .description("Kafka Consumer Group Identifier corresponding to Kafka group.id property")
            .required(true)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .expressionLanguageSupported(NONE)
            .build();

    static final PropertyDescriptor TOPIC_NAME = new PropertyDescriptor.Builder()
            .name("Topic Name")
            .displayName("Topic Name")
            .description("Name of the Kafka Topic from which the Processor consumes Kafka Records")
            .required(true)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .expressionLanguageSupported(NONE)
            .build();

    static final PropertyDescriptor AUTO_OFFSET_RESET = new PropertyDescriptor.Builder()
            .name("Auto Offset Reset")
            .displayName("Auto Offset Reset")
            .description("Automatic offset configuration applied when no previous consumer offset found corresponding to Kafka auto.offset.reset property")
            .required(true)
            .allowableValues(AutoOffsetReset.class)
            .defaultValue(AutoOffsetReset.LATEST.getValue())
            .expressionLanguageSupported(NONE)
            .build();

    static final PropertyDescriptor PROCESSING_STRATEGY = new PropertyDescriptor.Builder()
            .name("Processing Strategy")
            .displayName("Processing Strategy")
            .description("Strategy for processing Kafka Records and writing serialized output to FlowFiles")
            .required(true)
            .allowableValues(ProcessingStrategy.class)
            .defaultValue(ProcessingStrategy.FLOW_FILE.getValue())
            .expressionLanguageSupported(NONE)
            .build();

    static final Relationship SUCCESS = new Relationship.Builder()
            .name("success")
            .description("FlowFiles containing one or more serialized Kafka Records")
            .build();

    private static final List<PropertyDescriptor> DESCRIPTORS = List.of(
            CONNECTION_SERVICE,
            GROUP_ID,
            TOPIC_NAME,
            AUTO_OFFSET_RESET,
            PROCESSING_STRATEGY
    );

    private static final Set<Relationship> RELATIONSHIPS = Collections.singleton(SUCCESS);

    private static final String TRANSIT_URI_FORMAT = "kafka://%s/%s";

    private KafkaConsumerService consumerService;

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return DESCRIPTORS;
    }

    @Override
    public Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        final KafkaConnectionService connectionService = context.getProperty(CONNECTION_SERVICE).asControllerService(KafkaConnectionService.class);
        consumerService = connectionService.getConsumerService(new ConsumerConfiguration());
    }

    @OnStopped
    public void onStopped() {
        if (consumerService != null) {
            try {
                consumerService.close();
            } catch (final Exception e) {
                getLogger().warn("Closing Consumer Service failed", e);
            }
        }
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) {
        final PollingContext pollingContext = getPollingContext(context);

        final Iterator<ByteRecord> consumerRecords = consumerService.poll(pollingContext).iterator();
        if (consumerRecords.hasNext()) {
            processConsumerRecords(context, session, pollingContext, consumerRecords);
        } else {
            getLogger().debug("No Kafka Records consumed: {}", pollingContext);
            context.yield();
        }
    }

    @Override
    public List<ConfigVerificationResult> verify(final ProcessContext context, final ComponentLog verificationLogger, final Map<String, String> attributes) {
        final List<ConfigVerificationResult> verificationResults = new ArrayList<>();

        final KafkaConnectionService connectionService = context.getProperty(CONNECTION_SERVICE).asControllerService(KafkaConnectionService.class);
        final KafkaConsumerService consumerService = connectionService.getConsumerService(new ConsumerConfiguration());

        final ConfigVerificationResult.Builder verificationPartitions = new ConfigVerificationResult.Builder()
                .verificationStepName("Verify Topic Partitions");

        final PollingContext pollingContext = getPollingContext(context);

        try {
            final List<PartitionState> partitionStates = consumerService.getPartitionStates(pollingContext);
            verificationPartitions
                    .outcome(ConfigVerificationResult.Outcome.SUCCESSFUL)
                    .explanation(String.format("Partitions [%d] found for Topics %s", partitionStates.size(), pollingContext.getTopics()));
        } catch (final Exception e) {
            getLogger().error("Topics {} Partition verification failed", pollingContext.getTopics(), e);
            verificationPartitions
                    .outcome(ConfigVerificationResult.Outcome.FAILED)
                    .explanation(String.format("Topics %s Partition access failed: %s", pollingContext.getTopics(), e));
        }
        verificationResults.add(verificationPartitions.build());

        return verificationResults;
    }

    private void processConsumerRecords(final ProcessContext context, final ProcessSession session, final PollingContext pollingContext, final Iterator<ByteRecord> consumerRecords) {
        final ProcessingStrategy processingStrategy = ProcessingStrategy.valueOf(context.getProperty(PROCESSING_STRATEGY).getValue());
        if (ProcessingStrategy.FLOW_FILE == processingStrategy) {
            processFlowFileConsumerRecords(session, pollingContext, consumerRecords);
        } else {
            throw new ProcessException(String.format("Processing Strategy not supported [%s]", processingStrategy));
        }
    }

    private void processFlowFileConsumerRecords(final ProcessSession session, final PollingContext pollingContext, final Iterator<ByteRecord> consumerRecords) {
        final Map<TopicPartitionSummary, OffsetSummary> offsets = new LinkedHashMap<>();

        while (consumerRecords.hasNext()) {
            final ByteRecord consumerRecord = consumerRecords.next();

            final byte[] value = consumerRecord.getValue();
            FlowFile flowFile = session.create();
            flowFile = session.write(flowFile, outputStream -> outputStream.write(value));

            final Map<String, String> attributes = getAttributes(consumerRecord);
            flowFile = session.putAllAttributes(flowFile, attributes);

            final ProvenanceReporter provenanceReporter = session.getProvenanceReporter();
            final String transitUri = String.format(TRANSIT_URI_FORMAT, consumerRecord.getTopic(), consumerRecord.getPartition());
            provenanceReporter.receive(flowFile, transitUri);

            session.transfer(flowFile, SUCCESS);

            final TopicPartitionSummary topicPartitionSummary = new TopicPartitionSummary(consumerRecord.getTopic(), consumerRecord.getPartition());
            final long offset = consumerRecord.getOffset();
            final OffsetSummary offsetSummary = offsets.computeIfAbsent(topicPartitionSummary, (summary) -> new OffsetSummary(offset));
            offsetSummary.setOffset(offset);
        }

        final PollingSummary pollingSummary;
        if (pollingContext.getTopicPattern().isPresent()) {
            pollingSummary = new PollingSummary(pollingContext.getGroupId(), pollingContext.getTopicPattern().get(), pollingContext.getAutoOffsetReset(), offsets);
        } else {
            pollingSummary = new PollingSummary(pollingContext.getGroupId(), pollingContext.getTopics(), pollingContext.getAutoOffsetReset(), offsets);
        }
        session.commitAsync(() -> consumerService.commit(pollingSummary));
    }

    private Map<String, String> getAttributes(final ByteRecord consumerRecord) {
        final Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(KafkaFlowFileAttribute.KAFKA_TOPIC, consumerRecord.getTopic());
        attributes.put(KafkaFlowFileAttribute.KAFKA_PARTITION, Long.toString(consumerRecord.getPartition()));
        attributes.put(KafkaFlowFileAttribute.KAFKA_OFFSET, Long.toString(consumerRecord.getOffset()));
        attributes.put(KafkaFlowFileAttribute.KAFKA_TIMESTAMP, Long.toString(consumerRecord.getTimestamp()));

        for (final RecordHeader header : consumerRecord.getHeaders()) {
            final String value = new String(header.value(), StandardCharsets.UTF_8);
            attributes.put(header.key(), value);
        }

        return attributes;
    }

    private PollingContext getPollingContext(final ProcessContext context) {
        final String groupId = context.getProperty(GROUP_ID).getValue();
        final String topicName = context.getProperty(TOPIC_NAME).getValue();
        final String offsetReset = context.getProperty(AUTO_OFFSET_RESET).getValue();
        final AutoOffsetReset autoOffsetReset = AutoOffsetReset.valueOf(offsetReset.toUpperCase());

        return new PollingContext(groupId, Collections.singleton(topicName), autoOffsetReset);
    }
}
