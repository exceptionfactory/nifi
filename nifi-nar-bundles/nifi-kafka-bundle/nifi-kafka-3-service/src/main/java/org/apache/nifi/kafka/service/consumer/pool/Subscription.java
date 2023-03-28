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
package org.apache.nifi.kafka.service.consumer.pool;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Subscription for pooled Kafka Consumers
 */
public class Subscription {
    private final String groupId;

    private final Collection<String> topics;

    private final Pattern topicPattern;

    public Subscription(final String groupId, final Collection<String> topics) {
        this.groupId = Objects.requireNonNull(groupId, "Group ID required");
        this.topics = Collections.unmodifiableCollection(Objects.requireNonNull(topics, "Topics required"));
        this.topicPattern = null;
    }

    public Subscription(final String groupId, final Pattern topicPattern) {
        this.groupId = Objects.requireNonNull(groupId, "Group ID required");
        this.topics = Collections.emptyList();
        this.topicPattern = Objects.requireNonNull(topicPattern, "Topic Patten required");
    }

    public String getGroupId() {
        return groupId;
    }

    public Collection<String> getTopics() {
        return topics;
    }

    public Optional<Pattern> getTopicPattern() {
        return Optional.ofNullable(topicPattern);
    }

    @Override
    public boolean equals(final Object object) {
        final boolean equals;

        if (object == null) {
            equals = false;
        } else if (object instanceof Subscription) {
            final Subscription subscription = (Subscription) object;
            if (groupId.equals(subscription.groupId)) {
                if (topics.equals(subscription.topics)) {
                    equals = Objects.equals(topicPattern, subscription.topicPattern);
                } else {
                    equals = false;
                }
            } else {
                equals = false;
            }
        } else {
            equals = false;
        }

        return equals;
    }

    @Override
    public int hashCode() {
        final int hashCode;

        if (topicPattern == null) {
            hashCode = Arrays.hashCode(new Object[]{groupId, topics});
        } else {
            hashCode = Arrays.hashCode(new Object[]{groupId, topics, topicPattern});
        }

        return hashCode;
    }
}
