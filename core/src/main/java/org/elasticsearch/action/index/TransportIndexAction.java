/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.index;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkShardRequest;
import org.elasticsearch.action.bulk.BulkShardResponse;
import org.elasticsearch.action.bulk.SingleWriteOperationUtility.ResultHolder;
import org.elasticsearch.action.bulk.TransportBulkAction;
import org.elasticsearch.action.bulk.TransportShardBulkAction;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.replication.TransportWriteAction;
import org.elasticsearch.cluster.action.shard.ShardStateAction;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import static org.elasticsearch.action.bulk.SingleWriteOperationUtility.executeSingleItemBulkRequestOnReplica;
import static org.elasticsearch.action.bulk.SingleWriteOperationUtility.executeSingleItemBulkRequestOnPrimary;
import static org.elasticsearch.action.bulk.SingleWriteOperationUtility.toSingleItemBulkRequest;
import static org.elasticsearch.action.bulk.SingleWriteOperationUtility.wrapBulkResponse;

/**
 * Performs the index operation.
 *
 * Allows for the following settings:
 * <ul>
 * <li><b>autoCreateIndex</b>: When set to <tt>true</tt>, will automatically create an index if one does not exists.
 * Defaults to <tt>true</tt>.
 * <li><b>allowIdGeneration</b>: If the id is set not, should it be generated. Defaults to <tt>true</tt>.
 * </ul>
 *
 * Deprecated use TransportBulkAction with a single item instead
 */
@Deprecated
public class TransportIndexAction extends TransportWriteAction<IndexRequest, IndexRequest, IndexResponse> {

    private final TransportBulkAction bulkAction;
    private final TransportShardBulkAction shardBulkAction;

    @Inject
    public TransportIndexAction(Settings settings, TransportService transportService, ClusterService clusterService,
                                IndicesService indicesService,
                                ThreadPool threadPool, ShardStateAction shardStateAction,
                                ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                                TransportBulkAction bulkAction, TransportShardBulkAction shardBulkAction) {
        super(settings, IndexAction.NAME, transportService, clusterService, indicesService, threadPool, shardStateAction,
                actionFilters, indexNameExpressionResolver, IndexRequest::new, IndexRequest::new, ThreadPool.Names.INDEX);
        this.bulkAction = bulkAction;
        this.shardBulkAction = shardBulkAction;
    }

    @Override
    protected void doExecute(Task task, final IndexRequest request, final ActionListener<IndexResponse> listener) {
        bulkAction.execute(task, toSingleItemBulkRequest(request), wrapBulkResponse(listener));
    }

    @Override
    protected IndexResponse newResponseInstance() {
        return new IndexResponse();
    }

    @Override
    protected WritePrimaryResult<IndexRequest, IndexResponse> shardOperationOnPrimary(
            IndexRequest request, final IndexShard primary) throws Exception {
        ResultHolder<IndexResponse> resultHolder = executeSingleItemBulkRequestOnPrimary(request,
                bulkShardRequest -> {
                    WritePrimaryResult<BulkShardRequest, BulkShardResponse> result =
                            shardBulkAction.shardOperationOnPrimary(bulkShardRequest, primary);
                    return new Tuple<>(result.finalResponseIfSuccessful, result.location);
                }
        );
        return new WritePrimaryResult<>(request, resultHolder.response, resultHolder.location, resultHolder.failure, primary, logger);
    }

    @Override
    protected WriteReplicaResult<IndexRequest> shardOperationOnReplica(
            IndexRequest request, IndexShard replica) throws Exception {
        ResultHolder resultHolder = executeSingleItemBulkRequestOnReplica(request,
                bulkShardRequest -> {
                    WriteReplicaResult<BulkShardRequest> result = shardBulkAction.shardOperationOnReplica(bulkShardRequest, replica);
                    return result.location;
                }
        );
        return new WriteReplicaResult<>(request, resultHolder.location, null, replica, logger);
    }
}
