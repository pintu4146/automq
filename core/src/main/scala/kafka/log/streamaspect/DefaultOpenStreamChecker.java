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

package kafka.log.streamaspect;

import kafka.server.metadata.KRaftMetadataCache;

import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.errors.s3.StreamFencedException;
import org.apache.kafka.image.MetadataImage;
import org.apache.kafka.image.S3StreamMetadataImage;
import org.apache.kafka.image.TopicImage;
import org.apache.kafka.metadata.PartitionRegistration;

import com.automq.stream.s3.metadata.StreamState;

import java.util.Arrays;

public class DefaultOpenStreamChecker implements OpenStreamChecker {
    private final int nodeId;
    private final KRaftMetadataCache metadataCache;

    public DefaultOpenStreamChecker(int nodeId, KRaftMetadataCache metadataCache) {
        this.nodeId = nodeId;
        this.metadataCache = metadataCache;
    }

    @Override
    public boolean check(Uuid topicId, int partition, long streamId, long epoch) throws StreamFencedException {
        return metadataCache.safeRun(image -> DefaultOpenStreamChecker.check(image, topicId, partition, streamId, epoch, nodeId));
    }

    public static boolean check(MetadataImage image, Uuid topicId, int partition, long streamId, long epoch, int currentNodeId) throws StreamFencedException {
        // When ABA reassign happens:
        // 1. Assign P0 to broker0 with epoch=0, broker0 opens the partition
        // 2. Assign P0 to broker1 with epoch=1, broker1 waits for the partition to be closed
        // 3. Quick reassign P0 to broker0 with epoch=2, broker0 merge step2/3 image and keep stream opened with epoch=0
        // 4. So broker1 should check partition leader epoch to fail the waiting
        TopicImage topicImage = image.topics().getTopic(topicId);
        if (topicImage == null) {
            throw new StreamFencedException(String.format("topicId=%s cannot be found, it may be deleted or not created yet", topicId));
        }
        PartitionRegistration partitionImage = topicImage.partitions().get(partition);
        if (partitionImage == null) {
            throw new StreamFencedException(String.format("partition=%s-%d cannot be found, it may be deleted or not created yet", topicId, partition));
        }
        int currentEpoch = partitionImage.leaderEpoch;
        if (currentEpoch > epoch) {
            throw new StreamFencedException(String.format("partition=%s-%d with epoch=%d is fenced by new leader epoch=%d", topicId, partition, epoch, currentEpoch));
        }
        if (!contains(partitionImage.isr, currentNodeId)) {
            throw new StreamFencedException(String.format("partition=%s-%d with epoch=%d move to other nodes %s", topicId, partition, epoch, Arrays.toString(partitionImage.isr)));
        }

        S3StreamMetadataImage stream = image.streamsMetadata().getStreamMetadata(streamId);
        if (stream == null) {
            throw new StreamFencedException(String.format("streamId=%d cannot be found, it may be deleted or not created yet", streamId));
        }
        if (stream.getEpoch() > epoch)
            throw new StreamFencedException(String.format("streamId=%d with epoch=%d is fenced by new leader epoch=%d", streamId, epoch, stream.getEpoch()));
        return StreamState.CLOSED.equals(stream.state());
    }

    private static boolean contains(int[] isr, int nodeId) {
        if (isr == null) {
            return false;
        }
        for (int replica : isr) {
            if (replica == nodeId) {
                return true;
            }
        }
        return false;
    }
}
