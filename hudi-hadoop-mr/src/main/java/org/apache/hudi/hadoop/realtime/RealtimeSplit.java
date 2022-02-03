/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.hadoop.realtime;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.InputSplitWithLocationInfo;
import org.apache.hudi.common.model.HoodieLogFile;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.hadoop.InputSplitUtils;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Realtime Input Split Interface.
 */
public interface RealtimeSplit extends InputSplitWithLocationInfo {

  /**
   * Return Log File Paths.
   * @return
   */
  default List<String> getDeltaLogPaths() {
    return getDeltaLogFiles().stream().map(entry -> entry.getPath().toString()).collect(Collectors.toList());
  }

  List<HoodieLogFile> getDeltaLogFiles();

  void setDeltaLogFiles(List<HoodieLogFile> deltaLogFiles);

  /**
   * Return Max Instant Time.
   * @return
   */
  String getMaxCommitTime();

  /**
   * Return Base Path of the dataset.
   * @return
   */
  String getBasePath();

  /**
   * Returns Virtual key info if meta fields are disabled.
   * @return
   */
  Option<HoodieVirtualKeyInfo> getHoodieVirtualKeyInfo();

  /**
   * Update Maximum valid instant time.
   * @param maxCommitTime
   */
  void setMaxCommitTime(String maxCommitTime);

  /**
   * Set Base Path.
   * @param basePath
   */
  void setBasePath(String basePath);

  void setHoodieVirtualKeyInfo(Option<HoodieVirtualKeyInfo> hoodieVirtualKeyInfo);

  default void writeToOutput(DataOutput out) throws IOException {
    InputSplitUtils.writeString(getBasePath(), out);
    InputSplitUtils.writeString(getMaxCommitTime(), out);
    out.writeInt(getDeltaLogFiles().size());
    for (HoodieLogFile logFile : getDeltaLogFiles()) {
      InputSplitUtils.writeString(logFile.getPath().toString(), out);
      out.writeLong(logFile.getFileSize());
    }

    Option<HoodieVirtualKeyInfo> virtualKeyInfoOpt = getHoodieVirtualKeyInfo();
    if (!virtualKeyInfoOpt.isPresent()) {
      InputSplitUtils.writeBoolean(false, out);
    } else {
      InputSplitUtils.writeBoolean(true, out);
      InputSplitUtils.writeString(virtualKeyInfoOpt.get().getRecordKeyField(), out);
      InputSplitUtils.writeString(virtualKeyInfoOpt.get().getPartitionPathField(), out);
      InputSplitUtils.writeString(String.valueOf(virtualKeyInfoOpt.get().getRecordKeyFieldIndex()), out);
      InputSplitUtils.writeString(String.valueOf(virtualKeyInfoOpt.get().getPartitionPathFieldIndex()), out);
    }
  }

  default void readFromInput(DataInput in) throws IOException {
    setBasePath(InputSplitUtils.readString(in));
    setMaxCommitTime(InputSplitUtils.readString(in));

    int totalLogFiles = in.readInt();
    List<HoodieLogFile> deltaLogPaths = new ArrayList<>(totalLogFiles);
    for (int i = 0; i < totalLogFiles; i++) {
      String logFilePath = InputSplitUtils.readString(in);
      long logFileSize = in.readLong();
      deltaLogPaths.add(new HoodieLogFile(new Path(logFilePath), logFileSize));
    }
    setDeltaLogFiles(deltaLogPaths);

    boolean hoodieVirtualKeyPresent = InputSplitUtils.readBoolean(in);
    if (hoodieVirtualKeyPresent) {
      String recordKeyField = InputSplitUtils.readString(in);
      String partitionPathField = InputSplitUtils.readString(in);
      int recordFieldIndex = Integer.parseInt(InputSplitUtils.readString(in));
      int partitionPathIndex = Integer.parseInt(InputSplitUtils.readString(in));
      setHoodieVirtualKeyInfo(Option.of(new HoodieVirtualKeyInfo(recordKeyField, partitionPathField, recordFieldIndex, partitionPathIndex)));
    }
  }

  /**
   * The file containing this split's data.
   */
  public Path getPath();

  /**
   * The position of the first byte in the file to process.
   */
  public long getStart();

  /**
   * The number of bytes in the file to process.
   */
  public long getLength();
}
