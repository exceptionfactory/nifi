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

import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.ConfigVerificationResult;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.kafka.processors.producer.convert.DelimitedStreamKafkaRecordConverter;
import org.apache.nifi.kafka.processors.producer.convert.FlowFileStreamKafkaRecordConverter;
import org.apache.nifi.kafka.processors.producer.convert.KafkaRecordConverter;
import org.apache.nifi.kafka.processors.producer.convert.RecordStreamKafkaRecordConverter;
import org.apache.nifi.kafka.service.api.KafkaConnectionService;
import org.apache.nifi.kafka.service.api.common.PartitionState;
import org.apache.nifi.kafka.service.api.producer.KafkaProducerService;
import org.apache.nifi.kafka.service.api.producer.ProducerConfiguration;
import org.apache.nifi.kafka.service.api.producer.PublishContext;
import org.apache.nifi.kafka.service.api.record.KafkaRecord;
import org.apache.nifi.kafka.shared.attribute.KafkaFlowFileAttribute;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.DataUnit;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.VerifiableProcessor;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.serialization.RecordSetWriterFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.nifi.expression.ExpressionLanguageScope.NONE;

@Tags({"kafka", "producer", "record"})
public class PublishKafka extends AbstractProcessor implements VerifiableProcessor {

    static final AllowableValue UTF8_ENCODING = new AllowableValue("utf-8", "UTF-8 Encoded", "The key is interpreted as a UTF-8 Encoded string.");
    static final AllowableValue HEX_ENCODING = new AllowableValue("hex", "Hex Encoded",
            "The key is interpreted as arbitrary binary data that is encoded using hexadecimal characters with uppercase letters.");

    static final PropertyDescriptor CONNECTION_SERVICE = new PropertyDescriptor.Builder()
            .name("Kafka Connection Service")
            .displayName("Kafka Connection Service")
            .description("Provides connections to Kafka Broker for publishing Kafka Records")
            .identifiesControllerService(KafkaConnectionService.class)
            .expressionLanguageSupported(NONE)
            .required(true)
            .build();

    static final PropertyDescriptor TOPIC_NAME = new PropertyDescriptor.Builder()
            .name("Topic Name")
            .displayName("Topic Name")
            .description("Name of the Kafka Topic to which the Processor publishes Kafka Records")
            .required(true)
            .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    static final PropertyDescriptor RECORD_READER = new PropertyDescriptor.Builder()
            .name("record-reader")
            .displayName("Record Reader")
            .description("The Record Reader to use for incoming FlowFiles")
            .identifiesControllerService(RecordReaderFactory.class)
            .expressionLanguageSupported(NONE)
            .build();

    static final PropertyDescriptor RECORD_WRITER = new PropertyDescriptor.Builder()
            .name("record-writer")
            .displayName("Record Writer")
            .description("The Record Writer to use in order to serialize the data before sending to Kafka")
            .identifiesControllerService(RecordSetWriterFactory.class)
            .expressionLanguageSupported(NONE)
            .build();

    static final PropertyDescriptor MESSAGE_DEMARCATOR = new PropertyDescriptor.Builder()
            .name("message-demarcator")
            .displayName("Message Demarcator")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .description("Specifies the string (interpreted as UTF-8) to use for demarcating multiple messages within "
                    + "a single FlowFile. If not specified, the entire content of the FlowFile will be used as a single message. If specified, the "
                    + "contents of the FlowFile will be split on this delimiter and each section sent as a separate Kafka message. "
                    + "To enter special character such as 'new line' use CTRL+Enter or Shift+Enter, depending on your OS.")
            .build();

    static final PropertyDescriptor MAX_REQUEST_SIZE = new PropertyDescriptor.Builder()
            .name("max.request.size")
            .displayName("Max Request Size")
            .description("The maximum size of a request in bytes. Corresponds to Kafka's 'max.request.size' property and defaults to 1 MB (1048576).")
            .required(true)
            .addValidator(StandardValidators.DATA_SIZE_VALIDATOR)
            .defaultValue("1 MB")
            .build();

    static final PropertyDescriptor KEY = new PropertyDescriptor.Builder()
            .name("kafka-key")
            .displayName("Kafka Key")
            .description("The Key to use for the Message. "
                    + "If not specified, the flow file attribute 'kafka.key' is used as the message key, if it is present."
                    + "Beware that setting Kafka key and demarcating at the same time may potentially lead to many Kafka messages with the same key."
                    + "Normally this is not a problem as Kafka does not enforce or assume message and key uniqueness. Still, setting the demarcator and Kafka key at the same time poses a risk of "
                    + "data loss on Kafka. During a topic compaction on Kafka, messages will be deduplicated based on this key.")
            .required(false)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .build();

    static final PropertyDescriptor KEY_ATTRIBUTE_ENCODING = new PropertyDescriptor.Builder()
            .name("key-attribute-encoding")
            .displayName("Key Attribute Encoding")
            .description("FlowFiles that are emitted have an attribute named '" + KafkaFlowFileAttribute.KAFKA_KEY + "'. This property dictates how the value of the attribute should be encoded.")
            .required(true)
            .defaultValue(UTF8_ENCODING.getValue())
            .allowableValues(UTF8_ENCODING, HEX_ENCODING)
            .build();

    private static final List<PropertyDescriptor> DESCRIPTORS = Collections.unmodifiableList(Arrays.asList(
            CONNECTION_SERVICE,
            TOPIC_NAME,
            RECORD_READER,
            RECORD_WRITER,
            MESSAGE_DEMARCATOR,
            KEY,
            KEY_ATTRIBUTE_ENCODING,
            MAX_REQUEST_SIZE
    ));

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return DESCRIPTORS;
    }

    static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("FlowFiles for which all content was sent to Kafka.")
            .build();

    static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("Any FlowFile that cannot be sent to Kafka will be routed to this Relationship")
            .build();

    private static final Set<Relationship> RELATIONSHIPS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(REL_SUCCESS, REL_FAILURE)));

    @Override
    public Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        final FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        final KafkaConnectionService connectionService = context.getProperty(CONNECTION_SERVICE).asControllerService(KafkaConnectionService.class);
        final KafkaProducerService producerService = connectionService.getProducerService(new ProducerConfiguration());

        final RecordReaderFactory readerFactory = context.getProperty(RECORD_READER).asControllerService(RecordReaderFactory.class);
        final RecordSetWriterFactory writerFactory = context.getProperty(RECORD_WRITER).asControllerService(RecordSetWriterFactory.class);

        final String topicName = context.getProperty(TOPIC_NAME).evaluateAttributeExpressions(flowFile.getAttributes()).getValue();
        final PropertyValue propertyDemarcator = context.getProperty(MESSAGE_DEMARCATOR);
        final int maxMessageSize = context.getProperty(MAX_REQUEST_SIZE).asDataSize(DataUnit.B).intValue();

        final String keyAttribute = context.getProperty(KEY).getValue();
        final String keyAttributeEncoding = context.getProperty(KEY_ATTRIBUTE_ENCODING).getValue();

        final KafkaRecordConverter kafkaConverter = getRecordConverterFor(
                readerFactory, writerFactory, keyAttribute, keyAttributeEncoding, propertyDemarcator, flowFile, maxMessageSize);
        final PublishCallback callback = new PublishCallback(
                producerService, topicName, kafkaConverter, flowFile.getAttributes(), flowFile.getSize());

        session.read(flowFile, callback);
        session.transfer(flowFile, REL_SUCCESS);
    }

    private KafkaRecordConverter getRecordConverterFor(
            final RecordReaderFactory readerFactory, final RecordSetWriterFactory writerFactory,
            final String keyAttribute, final String keyAttributeEncoding,
            final PropertyValue propertyValueDemarcator, final FlowFile flowFile, final int maxMessageSize) {
        final KafkaRecordConverter kafkaConverter;
        if ((readerFactory != null) && (writerFactory != null)) {
            kafkaConverter = new RecordStreamKafkaRecordConverter(readerFactory, writerFactory, keyAttribute, keyAttributeEncoding, maxMessageSize, getLogger());
        } else if (propertyValueDemarcator.isSet()) {
            final String demarcator = propertyValueDemarcator.evaluateAttributeExpressions(flowFile).getValue();
            kafkaConverter = new DelimitedStreamKafkaRecordConverter(demarcator.getBytes(StandardCharsets.UTF_8), maxMessageSize);
        } else {
            kafkaConverter = new FlowFileStreamKafkaRecordConverter(maxMessageSize);
        }
        return kafkaConverter;
    }

    private static class PublishCallback implements InputStreamCallback {
        private final KafkaProducerService producerService;
        private final String topicName;
        private final KafkaRecordConverter kafkaConverter;
        private final Map<String, String> attributes;
        private final long inputLength;

        public PublishCallback(
                final KafkaProducerService producerService,
                final String topicName,
                final KafkaRecordConverter kafkaConverter,
                final Map<String, String> attributes,
                final long inputLength) {
            this.producerService = producerService;
            this.topicName = topicName;
            this.kafkaConverter = kafkaConverter;
            this.attributes = attributes;
            this.inputLength = inputLength;
        }

        @Override
        public void process(final InputStream in) throws IOException {
            try (final InputStream is = new BufferedInputStream(in)) {
                final Iterator<KafkaRecord> records = kafkaConverter.convert(attributes, is, inputLength);
                producerService.send(records, new PublishContext(topicName));
            }
        }
    }

    @Override
    public List<ConfigVerificationResult> verify(final ProcessContext context, final ComponentLog verificationLogger,
                                                 final Map<String, String> attributes) {
        final List<ConfigVerificationResult> verificationResults = new ArrayList<>();

        final KafkaConnectionService connectionService = context.getProperty(CONNECTION_SERVICE).asControllerService(KafkaConnectionService.class);
        final KafkaProducerService producerService = connectionService.getProducerService(new ProducerConfiguration());

        final ConfigVerificationResult.Builder verificationPartitions = new ConfigVerificationResult.Builder()
                .verificationStepName("Verify Topic Partitions");

        final String topicName = context.getProperty(TOPIC_NAME).evaluateAttributeExpressions(attributes).getValue();
        try {
            final List<PartitionState> partitionStates = producerService.getPartitionStates(topicName);
            verificationPartitions
                .outcome(ConfigVerificationResult.Outcome.SUCCESSFUL)
                .explanation(String.format("Partitions [%d] found for Topic [%s]", partitionStates.size(), topicName));
        } catch (final Exception e) {
            getLogger().error("Topic [%s] Partition verification failed", topicName, e);
            verificationPartitions
                .outcome(ConfigVerificationResult.Outcome.FAILED)
                .explanation(String.format("Topic [%s] Partition access failed: %s", topicName, e));
        }
        verificationResults.add(verificationPartitions.build());

        return verificationResults;
    }
}
