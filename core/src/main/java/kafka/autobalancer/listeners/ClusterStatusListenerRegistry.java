/*
 * Copyright 2025, AutoMQ HK Limited.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.autobalancer.listeners;

import java.util.ArrayList;
import java.util.List;

public class ClusterStatusListenerRegistry {
    private final List<LeaderChangeListener> leaderChangeListeners = new ArrayList<>();
    private final List<BrokerStatusListener> brokerListeners = new ArrayList<>();
    private final List<TopicPartitionStatusListener> topicPartitionListeners = new ArrayList<>();

    public void register(LeaderChangeListener listener) {
        leaderChangeListeners.add(listener);
    }

    public void register(BrokerStatusListener listener) {
        brokerListeners.add(listener);
    }

    public void register(TopicPartitionStatusListener listener) {
        topicPartitionListeners.add(listener);
    }

    public List<LeaderChangeListener> leaderChangeListeners() {
        return leaderChangeListeners;
    }

    public List<BrokerStatusListener> brokerListeners() {
        return brokerListeners;
    }

    public List<TopicPartitionStatusListener> topicPartitionListeners() {
        return topicPartitionListeners;
    }
}
