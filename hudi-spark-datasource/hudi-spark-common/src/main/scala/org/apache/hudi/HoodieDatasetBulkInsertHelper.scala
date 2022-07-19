/*
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

package org.apache.hudi

import org.apache.hudi.common.config.TypedProperties
import org.apache.hudi.common.model.HoodieRecord
import org.apache.hudi.common.util.ReflectionUtils
import org.apache.hudi.config.HoodieWriteConfig
import org.apache.hudi.keygen.BuiltinKeyGenerator
import org.apache.hudi.table.BulkInsertPartitioner
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow
import org.apache.spark.sql.types.{StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Dataset, HoodieUnsafeRDDUtils, Row, SQLContext}
import org.apache.spark.unsafe.types.UTF8String

import scala.collection.JavaConverters.asScalaBufferConverter

object HoodieDatasetBulkInsertHelper extends Logging {

  /**
   * Prepares [[DataFrame]] for bulk-insert into Hudi table, taking following steps:
   *
   * <ol>
   *   <li>Invoking configured [[KeyGenerator]] to produce record key, alas partition-path value</li>
   *   <li>Prepends Hudi meta-fields to every row in the dataset</li>
   *   <li>Dedupes rows (if necessary)</li>
   *   <li>Partitions dataset using provided [[partitioner]]</li>
   * </ol>
   */
  def prepareForBulkInsert(df: DataFrame,
                           config: HoodieWriteConfig,
                           partitioner: BulkInsertPartitioner[Dataset[Row]],
                           isGlobalIndex: Boolean,
                           dropPartitionColumns: Boolean): Dataset[Row] = {
    val populateMetaFields = config.populateMetaFields()
    val schema = df.schema

    val keyGeneratorClassName = config.getString(DataSourceWriteOptions.KEYGENERATOR_CLASS_NAME.key)

    val prependedRdd: RDD[InternalRow] =
      df.queryExecution.toRdd.mapPartitions { iter =>
        lazy val keyGenerator =
          ReflectionUtils.loadClass(keyGeneratorClassName, new TypedProperties(config.getProps))
            .asInstanceOf[BuiltinKeyGenerator]

        iter.map { row =>
          val (recordKey, partitionPath) =
            if (populateMetaFields) {
              (keyGenerator.getRecordKey(row, schema), keyGenerator.getPartitionPath(row, schema))
            } else {
              (UTF8String.EMPTY_UTF8, UTF8String.EMPTY_UTF8)
            }
          val commitTimestamp = UTF8String.EMPTY_UTF8
          val commitSeqNo = UTF8String.EMPTY_UTF8
          val filename = UTF8String.EMPTY_UTF8
          // To minimize # of allocations, we're going to allocate a single array
          // setting all column values in place for the updated row
          val newColVals = new Array[Any](schema.fields.length + HoodieRecord.HOODIE_META_COLUMNS.size)
          newColVals.update(0, recordKey)
          newColVals.update(1, partitionPath)
          newColVals.update(2, commitTimestamp)
          newColVals.update(3, commitSeqNo)
          newColVals.update(4, filename)
          // Prepend existing row column values
          row.toSeq(schema).copyToArray(newColVals, 5)
          new GenericInternalRow(newColVals)
        }
      }

    val metaFields = Seq(
        StructField(HoodieRecord.RECORD_KEY_METADATA_FIELD, StringType),
        StructField(HoodieRecord.PARTITION_PATH_METADATA_FIELD, StringType),
        StructField(HoodieRecord.COMMIT_TIME_METADATA_FIELD, StringType),
        StructField(HoodieRecord.COMMIT_SEQNO_METADATA_FIELD, StringType),
        StructField(HoodieRecord.FILENAME_METADATA_FIELD, StringType))

    val updatedSchema = StructType(metaFields ++ schema.fields)
    val updatedDF = HoodieUnsafeRDDUtils.createDataFrame(df.sparkSession, prependedRdd, updatedSchema)

    if (!populateMetaFields) {
      updatedDF
    } else {
      val trimmedDF = if (dropPartitionColumns) {
        val keyGenerator = ReflectionUtils.loadClass(keyGeneratorClassName, new TypedProperties(config.getProps)).asInstanceOf[BuiltinKeyGenerator]
        val partitionPathFields = keyGenerator.getPartitionPathFields.asScala
        val nestedPartitionPathFields = partitionPathFields.filter(f => f.contains('.'))
        if (nestedPartitionPathFields.nonEmpty) {
          logWarning(s"Can not drop nested partition path fields: $nestedPartitionPathFields")
        }

        val partitionPathCols = partitionPathFields -- nestedPartitionPathFields
        updatedDF.drop(partitionPathCols: _*)
      } else {
        updatedDF
      }

      val dedupedDF = if (config.shouldCombineBeforeInsert) {
        SparkRowWriteHelper.newInstance.deduplicateRows(trimmedDF, config.getPreCombineField, isGlobalIndex)
      } else {
        trimmedDF
      }

      partitioner.repartitionRecords(dedupedDF, config.getBulkInsertShuffleParallelism)
    }
  }
}
