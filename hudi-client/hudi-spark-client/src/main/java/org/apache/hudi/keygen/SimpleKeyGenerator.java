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

package org.apache.hudi.keygen;

import org.apache.avro.generic.GenericRecord;
import org.apache.hudi.common.config.TypedProperties;
import org.apache.hudi.keygen.constant.KeyGeneratorOptions;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.unsafe.types.UTF8String;

import java.util.Collections;

import static org.apache.hudi.keygen.KeyGenUtils.HUDI_DEFAULT_PARTITION_PATH;

/**
 * Simple key generator, which takes names of fields to be used for recordKey and partitionPath as configs.
 */
public class SimpleKeyGenerator extends BuiltinKeyGenerator {

  protected static final UTF8String HUDI_DEFAULT_PARTITION_PATH_UTF8 = UTF8String.fromString(HUDI_DEFAULT_PARTITION_PATH);

  private final SimpleAvroKeyGenerator simpleAvroKeyGenerator;

  public SimpleKeyGenerator(TypedProperties props) {
    this(props, props.getString(KeyGeneratorOptions.RECORDKEY_FIELD_NAME.key()),
        props.getString(KeyGeneratorOptions.PARTITIONPATH_FIELD_NAME.key()));
  }

  SimpleKeyGenerator(TypedProperties props, String partitionPathField) {
    this(props, null, partitionPathField);
  }

  SimpleKeyGenerator(TypedProperties props, String recordKeyField, String partitionPathField) {
    super(props);
    this.recordKeyFields = recordKeyField == null
        ? Collections.emptyList() : Collections.singletonList(recordKeyField);
    this.partitionPathFields = partitionPathField == null
        ? Collections.emptyList() : Collections.singletonList(partitionPathField);
    this.simpleAvroKeyGenerator = new SimpleAvroKeyGenerator(props, recordKeyField, partitionPathField);
  }

  @Override
  public String getRecordKey(GenericRecord record) {
    return simpleAvroKeyGenerator.getRecordKey(record);
  }

  @Override
  public String getPartitionPath(GenericRecord record) {
    return simpleAvroKeyGenerator.getPartitionPath(record);
  }

  @Override
  public String getRecordKey(Row row) {
    tryInitRowAccessor(row.schema());

    Object[] recordKeys = rowAccessor.getRecordKeyParts(row);
    // NOTE: [[SimpleKeyGenerator]] is restricted to allow only primitive (non-composite)
    //       record-key field
    if (recordKeys[0] == null) {
      return handleNullRecordKey();
    } else {
      return requireNonNullNonEmptyKey(recordKeys[0].toString());
    }
  }

  @Override
  public UTF8String getRecordKey(InternalRow internalRow, StructType schema) {
    tryInitRowAccessor(schema);

    Object[] recordKeyValues = rowAccessor.getRecordKeyParts(internalRow);
    // NOTE: [[SimpleKeyGenerator]] is restricted to allow only primitive (non-composite)
    //       record-key field
    if (recordKeyValues[0] == null) {
      return handleNullRecordKey();
    } else if (recordKeyValues[0] instanceof UTF8String) {
      return requireNonNullNonEmptyKey((UTF8String) recordKeyValues[0]);
    } else {
      return requireNonNullNonEmptyKey(UTF8String.fromString(recordKeyValues[0].toString()));
    }
  }

  @Override
  public String getPartitionPath(Row row) {
    tryInitRowAccessor(row.schema());

    Object[] partitionPathValues = rowAccessor.getRecordPartitionPathValues(row);
    // NOTE: [[SimpleKeyGenerator]] is restricted to allow only primitive (non-composite)
    //       partition-path field
    if (partitionPathValues[0] == null) {
      return combinePartitionPath(HUDI_DEFAULT_PARTITION_PATH);
    } else {
      return combinePartitionPath(partitionPathValues[0]);
    }
  }

  @Override
  public UTF8String getPartitionPath(InternalRow row, StructType schema) {
    tryInitRowAccessor(schema);

    Object[] partitionPathValues = rowAccessor.getRecordPartitionPathValues(row);
    // NOTE: [[SimpleKeyGenerator]] is restricted to allow only primitive (non-composite)
    //       partition-path field
    if (partitionPathValues[0] == null) {
      return combinePartitionPathUnsafe(HUDI_DEFAULT_PARTITION_PATH_UTF8);
    } else {
      return combinePartitionPathUnsafe(partitionPathValues[0].toString());
    }
  }
}
