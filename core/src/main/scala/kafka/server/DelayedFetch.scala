/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.server

import com.automq.stream.api.exceptions.FastReadFailFastException
import com.automq.stream.utils.FutureUtil
import com.yammer.metrics.core.Meter
import kafka.log.streamaspect.ReadHint
import kafka.server.DelayedFetch.DELAYED_FETCH_EXECUTOR
import kafka.server.streamaspect.ElasticReplicaManager

import java.util.concurrent.{ExecutorService, Executors, ThreadPoolExecutor, TimeUnit}
import org.apache.kafka.common.TopicIdPartition
import org.apache.kafka.common.errors._
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.requests.FetchRequest.PartitionData
import org.apache.kafka.common.requests.OffsetsForLeaderEpochResponse.{UNDEFINED_EPOCH, UNDEFINED_EPOCH_OFFSET}
import org.apache.kafka.common.utils.ThreadUtils
import org.apache.kafka.server.metrics.KafkaMetricsGroup
import org.apache.kafka.storage.internals.log.{FetchIsolation, FetchParams, FetchPartitionData, LogOffsetMetadata}

import scala.collection._
import scala.jdk.CollectionConverters._

case class FetchPartitionStatus(startOffsetMetadata: LogOffsetMetadata, fetchInfo: PartitionData) {

  override def toString: String = {
    "[startOffsetMetadata: " + startOffsetMetadata +
      ", fetchInfo: " + fetchInfo +
      "]"
  }
}

// AutoMQ for Kafka inject start
object DelayedFetch {
  private var DELAYED_FETCH_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(8, ThreadUtils.createThreadFactory("delayed-fetch-executor-%d", true))

  def executorQueueSize: Int = DELAYED_FETCH_EXECUTOR match {
    case tp: ThreadPoolExecutor => tp.getQueue.size
    case _ => 0
  }

  // Used by test
  def setFetchExecutor(executor: ExecutorService): Unit = {
    DELAYED_FETCH_EXECUTOR = executor
  }
}
// AutoMQ for Kafka inject end

/**
 * A delayed fetch operation that can be created by the replica manager and watched
 * in the fetch operation purgatory
 */
class DelayedFetch(
  params: FetchParams,
  fetchPartitionStatus: Seq[(TopicIdPartition, FetchPartitionStatus)],
  replicaManager: ReplicaManager,
  quota: ReplicaQuota,
  // AutoMQ for Kafka inject start
  limiter: Limiter = NoopLimiter.INSTANCE,
  // AutoMQ for Kafka inject end
  responseCallback: Seq[(TopicIdPartition, FetchPartitionData)] => Unit
) extends DelayedOperation(params.maxWaitMs) {

  override def toString: String = {
    s"DelayedFetch(params=$params" +
      s", numPartitions=${fetchPartitionStatus.size}" +
      ")"
  }

  /**
   * The operation can be completed if:
   *
   * Case A: This broker is no longer the leader for some partitions it tries to fetch
   * Case B: The replica is no longer available on this broker
   * Case C: This broker does not know of some partitions it tries to fetch
   * Case D: The partition is in an offline log directory on this broker
   * Case E: This broker is the leader, but the requested epoch is now fenced
   * Case F: The fetch offset locates not on the last segment of the log
   * Case G: The accumulated bytes from all the fetching partitions exceeds the minimum bytes
   * Case H: A diverging epoch was found, return response to trigger truncation
   * Upon completion, should return whatever data is available for each valid partition
   */
  override def tryComplete(): Boolean = {
    var accumulatedSize = 0
    fetchPartitionStatus.foreach {
      case (topicIdPartition, fetchStatus) =>
        val fetchOffset = fetchStatus.startOffsetMetadata
        val fetchLeaderEpoch = fetchStatus.fetchInfo.currentLeaderEpoch
        try {
          if (fetchOffset != LogOffsetMetadata.UNKNOWN_OFFSET_METADATA) {
            // AutoMQ for Kafka inject start
            val partition = replicaManager match {
              case manager: ElasticReplicaManager =>
                manager.getPartitionOrExceptionV2(topicIdPartition.topicPartition)
              case _ =>
                replicaManager.getPartitionOrException(topicIdPartition.topicPartition)
            }
            // AutoMQ for Kafka inject end
            val offsetSnapshot = partition.fetchOffsetSnapshot(fetchLeaderEpoch, params.fetchOnlyLeader)

            val endOffset = params.isolation match {
              case FetchIsolation.LOG_END => offsetSnapshot.logEndOffset
              case FetchIsolation.HIGH_WATERMARK => offsetSnapshot.highWatermark
              case FetchIsolation.TXN_COMMITTED => offsetSnapshot.lastStableOffset
            }

            // Go directly to the check for Case G if the message offsets are the same. If the log segment
            // has just rolled, then the high watermark offset will remain the same but be on the old segment,
            // which would incorrectly be seen as an instance of Case F.
            if (fetchOffset.messageOffset > endOffset.messageOffset) {
              // Case F, this can happen when the new fetch operation is on a truncated leader
              debug(s"Satisfying fetch $this since it is fetching later segments of partition $topicIdPartition.")
              return forceComplete()
            } else if (fetchOffset.messageOffset < endOffset.messageOffset) {
              if (fetchOffset.onOlderSegment(endOffset)) {
                // Case F, this can happen when the fetch operation is falling behind the current segment
                // or the partition has just rolled a new segment
                debug(s"Satisfying fetch $this immediately since it is fetching older segments.")
                // We will not force complete the fetch request if a replica should be throttled.
                if (!params.isFromFollower || !replicaManager.shouldLeaderThrottle(quota, partition, params.replicaId))
                  return forceComplete()
              } else if (fetchOffset.onSameSegment(endOffset)) {
                // we take the partition fetch size as upper bound when accumulating the bytes (skip if a throttled partition)
                val bytesAvailable = math.min(endOffset.positionDiff(fetchOffset), fetchStatus.fetchInfo.maxBytes)
                if (!params.isFromFollower || !replicaManager.shouldLeaderThrottle(quota, partition, params.replicaId))
                  accumulatedSize += bytesAvailable
              }
            }

            // Case H: If truncation has caused diverging epoch while this request was in purgatory, return to trigger truncation
            fetchStatus.fetchInfo.lastFetchedEpoch.ifPresent { fetchEpoch =>
              val epochEndOffset = partition.lastOffsetForLeaderEpoch(fetchLeaderEpoch, fetchEpoch, fetchOnlyFromLeader = false)
              if (epochEndOffset.errorCode != Errors.NONE.code()
                  || epochEndOffset.endOffset == UNDEFINED_EPOCH_OFFSET
                  || epochEndOffset.leaderEpoch == UNDEFINED_EPOCH) {
                debug(s"Could not obtain last offset for leader epoch for partition $topicIdPartition, epochEndOffset=$epochEndOffset.")
                return forceComplete()
              } else if (epochEndOffset.leaderEpoch < fetchEpoch || epochEndOffset.endOffset < fetchStatus.fetchInfo.fetchOffset) {
                debug(s"Satisfying fetch $this since it has diverging epoch requiring truncation for partition " +
                  s"$topicIdPartition epochEndOffset=$epochEndOffset fetchEpoch=$fetchEpoch fetchOffset=${fetchStatus.fetchInfo.fetchOffset}.")
                return forceComplete()
              }
            }
          }
        } catch {
          case _: NotLeaderOrFollowerException =>  // Case A or Case B
            debug(s"Broker is no longer the leader or follower of $topicIdPartition, satisfy $this immediately")
            return forceComplete()
          case _: UnknownTopicOrPartitionException => // Case C
            debug(s"Broker no longer knows of partition $topicIdPartition, satisfy $this immediately")
            return forceComplete()
          case _: KafkaStorageException => // Case D
            debug(s"Partition $topicIdPartition is in an offline log directory, satisfy $this immediately")
            return forceComplete()
          case _: FencedLeaderEpochException => // Case E
            debug(s"Broker is the leader of partition $topicIdPartition, but the requested epoch " +
              s"$fetchLeaderEpoch is fenced by the latest leader epoch, satisfy $this immediately")
            return forceComplete()
        }
    }

    // Case G
    if (accumulatedSize >= params.minBytes)
       forceComplete()
    else
      false
  }

  override def onExpiration(): Unit = {
    if (params.isFromFollower)
      DelayedFetchMetrics.followerExpiredRequestMeter.mark()
    else
      DelayedFetchMetrics.consumerExpiredRequestMeter.mark()
  }

  /**
   * Upon completion, read whatever data is available and pass to the complete callback
   */
  override def onComplete(): Unit = {
    // AutoMQ for Kafka inject start
    DELAYED_FETCH_EXECUTOR.submit(new Runnable {
      override def run(): Unit = {
        try {
          onComplete0()
        } catch {
          case e: Throwable => logger.error("delayed fetch fail", e)
        }
      }
    })
    // AutoMQ for Kafka inject end
  }

  private def onComplete0(): Unit = {
    val fetchInfos = fetchPartitionStatus.map { case (tp, status) =>
      tp -> status.fetchInfo
    }

    // AutoMQ for Kafka inject start
    ReadHint.markReadAll()
    // in delayed fetch, we only try fast read
    ReadHint.markFastRead()
    var logReadResults: Seq[(TopicIdPartition, LogReadResult)] = null
    try {
      logReadResults = replicaManager match {
        case manager: ElasticReplicaManager =>
          manager.readFromLocalLogV2(
            params,
            fetchInfos,
            quota,
            readFromPurgatory = true,
            limiter = limiter,
          )
        case _ =>
          replicaManager.readFromLog(
            params,
            fetchInfos,
            quota,
            readFromPurgatory = true
          )
      }
    } catch {
      case e: Throwable =>
        logReadResults = ElasticReplicaManager.emptyReadResults(fetchPartitionStatus.map(_._1))
        if (!FutureUtil.cause(e).isInstanceOf[FastReadFailFastException]) {
          error(s"Unexpected error in delayed fetch: $params $fetchInfos ", e)
        }
    }
    // AutoMQ for Kafka inject end

    val fetchPartitionData = logReadResults.map { case (tp, result) =>
      val isReassignmentFetch = params.isFromFollower &&
        replicaManager.isAddingReplica(tp.topicPartition, params.replicaId)

      tp -> result.toFetchPartitionData(isReassignmentFetch)
    }

    responseCallback(fetchPartitionData)
    // AutoMQ for Kafka inject start
    // clear hint after callback as it will be used in the callback
    //  see {@link ElasticKafkaApis#handleFetchRequest#processResponseCallback}
    ReadHint.clear()
    // AutoMQ for Kafka inject end
  }
}

object DelayedFetchMetrics {
  private val metricsGroup = new KafkaMetricsGroup(DelayedFetchMetrics.getClass)
  private val FetcherTypeKey = "fetcherType"
  val followerExpiredRequestMeter: Meter = metricsGroup.newMeter("ExpiresPerSec", "requests", TimeUnit.SECONDS, Map(FetcherTypeKey -> "follower").asJava)
  val consumerExpiredRequestMeter: Meter = metricsGroup.newMeter("ExpiresPerSec", "requests", TimeUnit.SECONDS, Map(FetcherTypeKey -> "consumer").asJava)
}
