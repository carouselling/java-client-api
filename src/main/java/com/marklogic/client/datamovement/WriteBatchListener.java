/*
 * Copyright 2015 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.client.datamovement;

import com.marklogic.client.DatabaseClient;

/**
 * Runs processEvent on each batch as it is ready during a WriteBatcher
 * job.
 *
 * @see #processEvent processEvent
 */
public interface WriteBatchListener extends BatchListener<WriteBatch> {
  /**
   * The method called by WriteBatcher to run your
   * custom code on this batch.  You usually implement this as a lambda expression.
   *
   * For example, see the lambda expression passed to onBatchSuccess:
   *
   *     WriteBatcher wb = dataMovementManager.newWriteBatcher(query)
   *         .withBatchSize(1000)
   *         .withThreadCount(20)
   *         .onBatchSuccess((client, batch) -&gt; {
   *             for ( WriteEvent doc : batch.getItems() ) {
   *                 if ( doc.getTargetUri().contains("/legal/") ) {
   *                     // do something
   *                 }
   *             }
   *         })
   *         .onBatchFailure((client, throwable) -&gt; throwable.printStackTrace());
   *
   * @param client the client pointed to the host containing this batch of uris
   * @param batch the batch of documents written and some metadata about the current status of the job
   */
  void processEvent(DatabaseClient client, WriteBatch batch);
}

