/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.io;

import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.common.engine.TaskContextSupplier;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.table.HoodieTable;

import org.apache.hadoop.fs.Path;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A {@link HoodieMergeHandle} that supports MERGE write incrementally(small data buffers).
 *
 * <p>For a new data buffer, it initialize and set up the next file path to write,
 * and closes the file path when the data buffer write finish. When next data buffer
 * write starts, it rolls over to another new file. If all the data buffers write finish
 * for a checkpoint round, it renames the last new file path as the desired file name
 * (name with the expected file ID).
 *
 * @see FlinkMergeAndReplaceHandle
 */
public class FlinkMergeHandle<T extends HoodieRecordPayload, I, K, O>
    extends HoodieMergeHandle<T, I, K, O>
    implements MiniBatchHandle {

  private static final Logger LOG = LogManager.getLogger(FlinkMergeHandle.class);

  private boolean isClosed = false;

  /**
   * Records the rolled over file paths.
   */
  private List<Path> rolloverPaths;

  public FlinkMergeHandle(HoodieWriteConfig config, String instantTime, HoodieTable<T, I, K, O> hoodieTable,
                          Iterator<HoodieRecord<T>> recordItr, String partitionPath, String fileId,
                          TaskContextSupplier taskContextSupplier) {
    super(config, instantTime, hoodieTable, recordItr, partitionPath, fileId, taskContextSupplier);
    if (rolloverPaths == null) {
      // #makeOldAndNewFilePaths may already initialize it already
      rolloverPaths = new ArrayList<>();
    }
    // delete invalid data files generated by task retry.
    if (getAttemptId() > 0) {
      deleteInvalidDataFile(getAttemptId() - 1);
    }
  }

  /**
   * The flink checkpoints start in sequence and asynchronously, when one write task finish the checkpoint(A)
   * (thus the fs view got the written data files some of which may be invalid),
   * it goes on with the next round checkpoint(B) write immediately,
   * if it tries to reuse the last small data bucket(small file) of an invalid data file,
   * finally, when the coordinator receives the checkpoint success event of checkpoint(A),
   * the invalid data file would be cleaned,
   * and this merger got a FileNotFoundException when it close the write file handle.
   *
   * <p> To solve, deletes the invalid data file eagerly
   * so that the invalid file small bucket would never be reused.
   *
   * @param lastAttemptId The last attempt ID
   */
  private void deleteInvalidDataFile(long lastAttemptId) {
    final String lastWriteToken = FSUtils.makeWriteToken(getPartitionId(), getStageId(), lastAttemptId);
    final String lastDataFileName = FSUtils.makeDataFileName(instantTime,
        lastWriteToken, this.fileId, hoodieTable.getBaseFileExtension());
    final Path path = makeNewFilePath(partitionPath, lastDataFileName);
    try {
      if (fs.exists(path)) {
        LOG.info("Deleting invalid MERGE base file due to task retry: " + lastDataFileName);
        fs.delete(path, false);
      }
    } catch (IOException e) {
      throw new HoodieException("Error while deleting the MERGE base file due to task retry: " + lastDataFileName, e);
    }
  }

  @Override
  protected void makeOldAndNewFilePaths(String partitionPath, String oldFileName, String newFileName) {
    // If the data file already exists, it means the write task write merge data bucket multiple times
    // in one hoodie commit, rolls over to a new name instead.

    // Use the existing file path as the base file path (file1),
    // and generates new file path with roll over number (file2).
    // the incremental data set would merge into the file2 instead of file1.
    //
    // When the task finalizes in #finishWrite, the intermediate files would be cleaned.
    super.makeOldAndNewFilePaths(partitionPath, oldFileName, newFileName);
    rolloverPaths = new ArrayList<>();
    try {
      int rollNumber = 0;
      while (fs.exists(newFilePath)) {
        oldFilePath = newFilePath; // override the old file name
        rolloverPaths.add(oldFilePath);
        newFileName = newFileNameWithRollover(rollNumber++);
        newFilePath = makeNewFilePath(partitionPath, newFileName);
        LOG.warn("Duplicate write for MERGE bucket with path: " + oldFilePath + ", rolls over to new path: " + newFilePath);
      }
    } catch (IOException e) {
      throw new HoodieException("Checking existing path for merge handle error: " + newFilePath, e);
    }
  }

  /**
   * Use the writeToken + "-" + rollNumber as the new writeToken of a mini-batch write.
   */
  protected String newFileNameWithRollover(int rollNumber) {
    // make the intermediate file as hidden
    return FSUtils.makeDataFileName(instantTime, writeToken + "-" + rollNumber,
        this.fileId, hoodieTable.getBaseFileExtension());
  }

  @Override
  public List<WriteStatus> close() {
    try {
      List<WriteStatus> writeStatus = super.close();
      finalizeWrite();
      return writeStatus;
    } finally {
      this.isClosed = true;
    }
  }

  boolean needsUpdateLocation() {
    // No need to update location for Flink hoodie records because all the records are pre-tagged
    // with the desired locations.
    return false;
  }

  public void finalizeWrite() {
    // The file visibility should be kept by the configured ConsistencyGuard instance.
    rolloverPaths.add(newFilePath);
    if (rolloverPaths.size() == 1) {
      // only one flush action, no need to roll over
      return;
    }

    for (int i = 0; i < rolloverPaths.size() - 1; i++) {
      Path path = rolloverPaths.get(i);
      try {
        fs.delete(path, false);
      } catch (IOException e) {
        throw new HoodieIOException("Error when clean the temporary roll file: " + path, e);
      }
    }
    final Path lastPath = rolloverPaths.get(rolloverPaths.size() - 1);
    final Path desiredPath = rolloverPaths.get(0);
    try {
      fs.rename(lastPath, desiredPath);
    } catch (IOException e) {
      throw new HoodieIOException("Error when rename the temporary roll file: " + lastPath + " to: " + desiredPath, e);
    }
  }

  @Override
  public void closeGracefully() {
    if (isClosed) {
      return;
    }
    try {
      close();
    } catch (Throwable throwable) {
      LOG.warn("Error while trying to dispose the MERGE handle", throwable);
      try {
        fs.delete(newFilePath, false);
        LOG.info("Deleting the intermediate MERGE data file: " + newFilePath + " success!");
      } catch (IOException e) {
        // logging a warning and ignore the exception.
        LOG.warn("Deleting the intermediate MERGE data file: " + newFilePath + " failed", e);
      }
    }
  }

  @Override
  public Path getWritePath() {
    return newFilePath;
  }
}