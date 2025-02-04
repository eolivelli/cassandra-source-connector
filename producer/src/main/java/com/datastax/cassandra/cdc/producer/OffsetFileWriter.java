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

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class OffsetFileWriter implements AutoCloseable {
    public static final String COMMITLOG_OFFSET_FILE = "commitlog_offset.dat";

    private final File offsetFile;
    private AtomicReference<CommitLogPosition> fileOffsetRef = new AtomicReference<>(new CommitLogPosition(0,0));

    private final OffsetFlushPolicy offsetFlushPolicy;
    volatile long timeOfLastFlush = System.currentTimeMillis();
    volatile Long notCommittedEvents = 0L;

    public OffsetFileWriter(String cdcLogDir) throws IOException {
        this.offsetFlushPolicy = new OffsetFlushPolicy.AlwaysFlushOffsetPolicy();
        /*
        this.meterRegistry.gauge("committed_segment", fileOffsetRef, new ToDoubleFunction<AtomicReference<CommitLogPosition>>() {
            @Override
            public double applyAsDouble(AtomicReference<CommitLogPosition> offsetPositionRef) {
                return offsetPositionRef.get().segmentId;
            }
        });
        this.meterRegistry.gauge("committed_position", fileOffsetRef, new ToDoubleFunction<AtomicReference<CommitLogPosition>>() {
            @Override
            public double applyAsDouble(AtomicReference<CommitLogPosition> offsetPositionRef) {
                return offsetPositionRef.get().position;
            }
        });
         */

        this.offsetFile = new File(cdcLogDir, COMMITLOG_OFFSET_FILE);
        init();
    }

    public CommitLogPosition offset() {
        return this.fileOffsetRef.get();
    }

    public void markOffset(CommitLogPosition sourceOffset) {
        this.fileOffsetRef.set(sourceOffset);
    }

    public void flush() throws IOException {
        saveOffset();
    }

    @Override
    public void close() throws IOException {
        saveOffset();
    }

    public static String serializePosition(CommitLogPosition commitLogPosition) {
        return Long.toString(commitLogPosition.segmentId) + File.pathSeparatorChar + Integer.toString(commitLogPosition.position);
    }

    public static CommitLogPosition deserializePosition(String s) {
        String[] segAndPos = s.split(Character.toString(File.pathSeparatorChar));
        return new CommitLogPosition(Long.parseLong(segAndPos[0]), Integer.parseInt(segAndPos[1]));
    }

    private synchronized void saveOffset() throws IOException {
        try(FileWriter out = new FileWriter(this.offsetFile)) {
            out.write(serializePosition(fileOffsetRef.get()));
        } catch (IOException e) {
            log.error("Failed to save offset for file " + offsetFile.getName(), e);
            throw e;
        }
    }

    private synchronized void loadOffset() throws IOException {
        try(BufferedReader br = new BufferedReader(new FileReader(offsetFile)))
        {
            fileOffsetRef.set(deserializePosition(br.readLine()));
            log.debug("file offset={}", fileOffsetRef.get());
        } catch (IOException e) {
            log.error("Failed to load offset for file " + offsetFile.getName(), e);
            throw e;
        }
    }

    private synchronized void init() throws IOException {
        if (offsetFile.exists()) {
            loadOffset();
        } else {
            Path parentPath = offsetFile.toPath().getParent();
            if (!parentPath.toFile().exists())
                Files.createDirectories(parentPath);
            saveOffset();
        }
    }

    void maybeCommitOffset(Mutation<?> record) {
        try {
            long now = System.currentTimeMillis();
            long timeSinceLastFlush = now - timeOfLastFlush;
            if(offsetFlushPolicy.shouldFlush(Duration.ofMillis(timeSinceLastFlush), notCommittedEvents)) {
                SourceInfo source = record.getSource();
                markOffset(source.commitLogPosition);
                flush();
                notCommittedEvents = 0L;
                timeOfLastFlush = now;
                log.debug("Offset flushed source=" + source);
            }
        } catch(IOException e) {
            log.warn("error:", e);
        }
    }
}
