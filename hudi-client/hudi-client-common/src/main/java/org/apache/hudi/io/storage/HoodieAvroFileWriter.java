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

package org.apache.hudi.io.storage;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import org.apache.hudi.avro.HoodieAvroUtils;
import org.apache.hudi.common.io.storage.HoodieRecordFileWriter;
import org.apache.hudi.common.model.HoodieRecord;

import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

public interface HoodieAvroFileWriter extends HoodieRecordFileWriter<IndexedRecord> {

  long getBytesWritten();

  // TODO rename
  @Override
  default void writeWithMetadata(HoodieRecord record, Schema schema, Properties props) throws IOException {
    record.writeWithMetadata(this, schema, props);
  }

  // TODO rename
  @Override
  default void write(HoodieRecord record, Schema schema, Properties props) throws IOException {
    record.write(this, schema, props);
  }

  default void prepRecordWithMetadata(IndexedRecord avroRecord, HoodieRecord record, String instantTime, Integer partitionId, AtomicLong recordIndex, String fileName) {
    String seqId = HoodieRecord.generateSequenceId(instantTime, partitionId, recordIndex.getAndIncrement());
    HoodieAvroUtils.addHoodieKeyToRecord((GenericRecord) avroRecord, record.getRecordKey(), record.getPartitionPath(), fileName);
    HoodieAvroUtils.addCommitMetadataToRecord((GenericRecord) avroRecord, instantTime, seqId);
    return;
  }
}
