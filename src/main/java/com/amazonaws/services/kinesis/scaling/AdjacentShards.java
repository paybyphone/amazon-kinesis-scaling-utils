/**
 * Amazon Kinesis Scaling Utility
 *
 * Copyright 2014, Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Amazon Software License (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/asl/
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazonaws.services.kinesis.scaling;

import java.math.BigInteger;
import java.util.Map;

import com.amazonaws.services.kinesis.AmazonKinesisClient;
import com.amazonaws.services.kinesis.model.LimitExceededException;
import com.amazonaws.services.kinesis.model.ResourceInUseException;

/**
 * AdjacentShards are a transfer object for maintaining references between an
 * open shard, and it's lower and higher neighbours by partition hash value
 */
public class AdjacentShards {
    private String streamName;

    private ShardHashInfo lowerShard;

    private ShardHashInfo higherShard;

    public AdjacentShards(String streamName, ShardHashInfo lower, ShardHashInfo higher)
            throws Exception {
        // ensure that the shards are adjacent
        if (!new BigInteger(higher.getShard().getHashKeyRange().getStartingHashKey()).subtract(
                new BigInteger(lower.getShard().getHashKeyRange().getEndingHashKey())).equals(
                new BigInteger("1"))) {
            throw new Exception("Shards are not Adjacent");
        }
        this.streamName = streamName;
        this.lowerShard = lower;
        this.higherShard = higher;
    }

    protected ShardHashInfo getLowerShard() {
        return lowerShard;
    }

    protected ShardHashInfo getHigherShard() {
        return higherShard;
    }

    /**
     * Merge these two Shards and return the result Shard
     * 
     * @param kinesisClient
     * @return
     * @throws Exception
     */
    protected ShardHashInfo doMerge(AmazonKinesisClient kinesisClient) throws Exception {
        boolean done = false;
        int mergeAttempts = 0;
        do {
            mergeAttempts++;
            try {
                kinesisClient.mergeShards(streamName, this.lowerShard.getShardId(),
                        this.higherShard.getShardId());

                StreamScalingUtils.waitForStreamStatus(kinesisClient, streamName, "ACTIVE");
            } catch (ResourceInUseException e) {
                // thrown when the Shard is mutating - wait until we are able to
                // do the modification or ResourceNotFoundException is thrown
                Thread.sleep(1000);
            } catch (LimitExceededException lee) {
                Thread.sleep(new Double(Math.pow(2, mergeAttempts)
                        * StreamScalingUtils.RETRY_TIMEOUT_MS).longValue());
            }
            done = true;
        } while (!done && mergeAttempts < StreamScalingUtils.MODIFY_RETRIES);

        if (!done) {
            throw new Exception(String.format("Unable to Merge Shards after %s Retries",
                    StreamScalingUtils.MODIFY_RETRIES));
        }

        Map<String, ShardHashInfo> openShards = StreamScalingUtils.getOpenShards(kinesisClient,
                streamName);

        for (ShardHashInfo info : openShards.values()) {
            if (lowerShard.getShardId().equals(info.getShard().getParentShardId())
                    && higherShard.getShardId().equals(info.getShard().getAdjacentParentShardId())) {
                return new ShardHashInfo(streamName, info.getShard());
            }
        }

        return null;
    }
}
