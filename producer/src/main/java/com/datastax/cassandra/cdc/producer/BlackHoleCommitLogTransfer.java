/**
 * Copyright DataStax, Inc 2021.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.cassandra.cdc.producer;

import java.io.File;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Implementation of {@link CommitLogTransfer} which deletes commit logs.
 */
public class BlackHoleCommitLogTransfer implements CommitLogTransfer {

    @Override
    public void onSuccessTransfer(File file) {
        CommitLogUtil.moveCommitLog(file, Paths.get(ProducerConfig.cdcRelocationDir));
    }

    @Override
    public void onErrorTransfer(File file) {
        CommitLogUtil.deleteCommitLog(file);
    }

    @Override
    public void getErrorCommitLogFiles() {
    }
}
