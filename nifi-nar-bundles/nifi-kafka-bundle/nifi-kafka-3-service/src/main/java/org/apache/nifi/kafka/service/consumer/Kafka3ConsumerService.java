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
package org.apache.nifi.kafka.service.consumer;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.nifi.kafka.service.api.common.PartitionState;
import org.apache.nifi.kafka.service.api.consumer.KafkaConsumerService;
import org.apache.nifi.kafka.service.api.consumer.PollingContext;
import org.apache.nifi.kafka.service.api.record.ByteRecord;
import org.apache.nifi.kafka.service.api.record.RecordSummary;
import org.apache.nifi.kafka.service.consumer.pool.ConsumerObjectPool;
import org.apache.nifi.kafka.service.consumer.pool.Subscription;
import org.apache.nifi.logging.ComponentLog;

import java.io.Closeable;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Kafka3ConsumerService implements KafkaConsumerService, Closeable {
    private final ComponentLog componentLog;

    private final ConsumerObjectPool consumerObjectPool;

    public Kafka3ConsumerService(final ComponentLog componentLog, final Properties properties) {
        this.componentLog = Objects.requireNonNull(componentLog, "Component Log required");
        this.consumerObjectPool = new ConsumerObjectPool(properties);
    }

    @Override
    public void commit(RecordSummary recordSummary) {
    }

    @Override
    public Iterable<ByteRecord> poll(PollingContext pollingContext) {
        return null;
    }

    @Override
    public List<PartitionState> getPartitionStates(final PollingContext pollingContext) {
        final String groupId = pollingContext.getGroupId();
        final Optional<Pattern> topicPatternFound = pollingContext.getTopicPattern();

        final Subscription subscription = topicPatternFound
                .map(pattern -> new Subscription(groupId, pattern))
                .orElseGet(() -> new Subscription(groupId, pollingContext.getTopics()));

        final Iterator<String> topics = subscription.getTopics().iterator();

        final List<PartitionState> partitionStates;

        if (topics.hasNext()) {
            final String topic = topics.next();
            partitionStates = runConsumerFunction(subscription, (consumer) ->
                    consumer.partitionsFor(topic)
                            .stream()
                            .map(partitionInfo -> new PartitionState(partitionInfo.topic(), partitionInfo.partition()))
                            .collect(Collectors.toList())
            );
        } else {
            partitionStates = Collections.emptyList();
        }

        return partitionStates;
    }

    @Override
    public void close() {
        consumerObjectPool.close();
    }

    private <T> T runConsumerFunction(final Subscription subscription, final Function<Consumer<byte[], byte[]>, T> consumerFunction) {
        Consumer<byte[], byte[]> consumer = null;
        try {
            consumer = consumerObjectPool.borrowObject(subscription);
            return consumerFunction.apply(consumer);
        } catch (final Exception e) {
            throw new ConsumerException("Borrow Consumer failed", e);
        } finally {
            if (consumer != null) {
                try {
                    consumerObjectPool.returnObject(subscription, consumer);
                } catch (final Exception e) {
                    componentLog.warn("Return Consumer failed", e);
                }
            }
        }
    }
}
