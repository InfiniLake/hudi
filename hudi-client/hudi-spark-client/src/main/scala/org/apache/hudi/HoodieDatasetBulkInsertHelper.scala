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

import org.apache.hudi.client.WriteStatus
import org.apache.hudi.client.model.HoodieInternalRow
import org.apache.hudi.common.config.TypedProperties
import org.apache.hudi.common.data.HoodieData
import org.apache.hudi.common.engine.TaskContextSupplier
import org.apache.hudi.common.model.HoodieRecord
import org.apache.hudi.common.util.ReflectionUtils
import org.apache.hudi.config.HoodieWriteConfig
import org.apache.hudi.exception.HoodieException
import org.apache.hudi.keygen.{BuiltinKeyGenerator, SparkKeyGeneratorInterface}
import org.apache.hudi.table.action.commit.{BulkInsertDataInternalWriterHelper, ParallelismHelper}
import org.apache.hudi.table.{BulkInsertPartitioner, HoodieTable}
import org.apache.hudi.util.JFunction.toJavaSerializableFunctionUnchecked
import org.apache.spark.Partitioner
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.HoodieCatalystExpressionUtils.generateUnsafeProjection
import org.apache.spark.sql.HoodieDataTypeUtils.{addMetaFields, hasMetaFields}
import org.apache.spark.sql.HoodieUnsafeRowUtils.{composeNestedFieldPath, getNestedInternalRowValue}
import org.apache.spark.sql.HoodieUnsafeUtils.getNumPartitions
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Alias, Literal}
import org.apache.spark.sql.catalyst.plans.logical.Project
import org.apache.spark.sql.types.{StringType, StructType}
import org.apache.spark.sql.{DataFrame, Dataset, HoodieUnsafeUtils, Row}
import org.apache.spark.unsafe.types.UTF8String

import scala.collection.JavaConverters.{asScalaBufferConverter, seqAsJavaListConverter}

object HoodieDatasetBulkInsertHelper
  extends ParallelismHelper[DataFrame](toJavaSerializableFunctionUnchecked(df => getNumPartitions(df))) with Logging {

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
                           isTablePartitioned: Boolean,
                           shouldDropPartitionColumns: Boolean): Dataset[Row] = {
    val populateMetaFields = config.populateMetaFields()

    val schema = df.schema
    val populatedSchema = addMetaFields(schema)

    val updatedDF = if (populateMetaFields) {
      val keyGeneratorClassName = config.getStringOrThrow(HoodieWriteConfig.KEYGENERATOR_CLASS_NAME,
        "Key-generator class name is required")
      val sourceRdd = df.queryExecution.toRdd
      val populatedRdd: RDD[InternalRow] = if (hasMetaFields(schema)) {
        sourceRdd
      } else {
        sourceRdd.mapPartitions { iter =>
          val keyGenerator =
            ReflectionUtils.loadClass(keyGeneratorClassName, new TypedProperties(config.getProps))
              .asInstanceOf[SparkKeyGeneratorInterface]
          val unsafeProjection = generateUnsafeProjection(populatedSchema, populatedSchema)

          iter.map { row =>
            val recordKey = keyGenerator.getRecordKey(row, schema)
            val partitionPath = keyGenerator.getPartitionPath(row, schema)
            val commitTimestamp = UTF8String.EMPTY_UTF8
            val commitSeqNo = UTF8String.EMPTY_UTF8
            val filename = UTF8String.EMPTY_UTF8

            // TODO use mutable row, avoid re-allocating
            val populatedRow = new HoodieInternalRow(commitTimestamp, commitSeqNo, recordKey, partitionPath, filename,
              row, false)
            // TODO elaborate
            unsafeProjection(populatedRow)
          }
        }
      }

      val dedupedRdd = if (config.shouldCombineBeforeInsert) {
        dedupRows(populatedRdd, populatedSchema, config.getPreCombineField, isTablePartitioned)
      } else {
        populatedRdd
      }

      HoodieUnsafeUtils.createDataFrameFromRDD(df.sparkSession, dedupedRdd, populatedSchema)
    } else {
      // NOTE: In cases when we're not populating meta-fields we actually don't
      //       need access to the [[InternalRow]] and therefore can avoid the need
      //       to dereference [[DataFrame]] into [[RDD]]
      val query = df.queryExecution.logical
      val metaFieldsStubs = HoodieRecord.HOODIE_META_COLUMNS.asScala
        .map(metaFieldName => Alias(Literal(UTF8String.EMPTY_UTF8, dataType = StringType), metaFieldName)())
      val prependedQuery = Project(metaFieldsStubs ++ query.output, query)

      HoodieUnsafeUtils.createDataFrameFrom(df.sparkSession, prependedQuery)
    }

    val trimmedDF = if (shouldDropPartitionColumns) {
      dropPartitionColumns(updatedDF, config)
    } else {
      updatedDF
    }

    val targetParallelism =
      deduceShuffleParallelism(trimmedDF, config.getBulkInsertShuffleParallelism)

    partitioner.repartitionRecords(trimmedDF, targetParallelism)
  }

  /**
   * Perform bulk insert for [[Dataset<Row>]], will not change timeline/index, return
   * information about write files.
   */
  def bulkInsert(dataset: Dataset[Row],
                 instantTime: String,
                 table: HoodieTable[_, _, _, _],
                 writeConfig: HoodieWriteConfig,
                 partitioner: BulkInsertPartitioner[Dataset[Row]],
                 parallelism: Int,
                 shouldPreserveHoodieMetadata: Boolean): HoodieData[WriteStatus] = {
    val repartitionedDataset = partitioner.repartitionRecords(dataset, parallelism)
    val arePartitionRecordsSorted = partitioner.arePartitionRecordsSorted
    val schema = dataset.schema
    val writeStatuses = repartitionedDataset.queryExecution.toRdd.mapPartitions(iter => {
      val taskContextSupplier: TaskContextSupplier = table.getTaskContextSupplier
      val taskPartitionId = taskContextSupplier.getPartitionIdSupplier.get
      val taskId = taskContextSupplier.getStageIdSupplier.get.toLong
      val taskEpochId = taskContextSupplier.getAttemptIdSupplier.get
      val writer = new BulkInsertDataInternalWriterHelper(
        table,
        writeConfig,
        instantTime,
        taskPartitionId,
        taskId,
        taskEpochId,
        schema,
        writeConfig.populateMetaFields,
        arePartitionRecordsSorted,
        shouldPreserveHoodieMetadata)

      try {
        iter.foreach(writer.write)
      } catch {
        case t: Throwable =>
          writer.abort()
          throw t
      } finally {
        writer.close()
      }

      writer.getWriteStatuses.asScala.map(_.toWriteStatus).iterator
    }).collect()
    table.getContext.parallelize(writeStatuses.toList.asJava)
  }

  private def dedupRows(rdd: RDD[InternalRow], schema: StructType, preCombineFieldRef: String, isPartitioned: Boolean): RDD[InternalRow] = {
    val recordKeyMetaFieldOrd = schema.fieldIndex(HoodieRecord.RECORD_KEY_METADATA_FIELD)
    val partitionPathMetaFieldOrd = schema.fieldIndex(HoodieRecord.PARTITION_PATH_METADATA_FIELD)
    // NOTE: Pre-combine field could be a nested field
    val preCombineFieldPath = composeNestedFieldPath(schema, preCombineFieldRef)
      .getOrElse(throw new HoodieException(s"Pre-combine field $preCombineFieldRef is missing in $schema"))

    rdd.map { row =>
      val partitionPath = if (isPartitioned) row.getUTF8String(partitionPathMetaFieldOrd) else UTF8String.EMPTY_UTF8
      val recordKey = row.getUTF8String(recordKeyMetaFieldOrd)

      ((partitionPath, recordKey), row)
    }
    // TODO elaborate
    .reduceByKey(TablePartitioningAwarePartitioner(rdd.getNumPartitions, isPartitioned),
      (oneRow, otherRow) => {
        val onePreCombineVal = getNestedInternalRowValue(oneRow, preCombineFieldPath).asInstanceOf[Comparable[AnyRef]]
        val otherPreCombineVal = getNestedInternalRowValue(otherRow, preCombineFieldPath).asInstanceOf[Comparable[AnyRef]]
        if (onePreCombineVal.compareTo(otherPreCombineVal.asInstanceOf[AnyRef]) >= 0) {
          oneRow
        } else {
          otherRow
        }
      })
    .values
  }

  private def dropPartitionColumns(df: DataFrame, config: HoodieWriteConfig): DataFrame = {
    val partitionPathFields = getPartitionPathFields(config).toSet
    val nestedPartitionPathFields = partitionPathFields.filter(f => f.contains('.'))
    if (nestedPartitionPathFields.nonEmpty) {
      logWarning(s"Can not drop nested partition path fields: $nestedPartitionPathFields")
    }

    val partitionPathCols = (partitionPathFields -- nestedPartitionPathFields).toSeq

    df.drop(partitionPathCols: _*)
  }

  private def getPartitionPathFields(config: HoodieWriteConfig): Seq[String] = {
    val keyGeneratorClassName = config.getString(HoodieWriteConfig.KEYGENERATOR_CLASS_NAME)
    val keyGenerator = ReflectionUtils.loadClass(keyGeneratorClassName, new TypedProperties(config.getProps)).asInstanceOf[BuiltinKeyGenerator]
    keyGenerator.getPartitionPathFields.asScala
  }

  private case class TablePartitioningAwarePartitioner(override val numPartitions: Int,
                                                       val isPartitioned: Boolean) extends Partitioner {
    override def getPartition(key: Any): Int = {
      key match {
        case null => 0
        case (partitionPath, recordKey) =>
          val targetKey = if (isPartitioned) partitionPath else recordKey
          nonNegativeMod(targetKey.hashCode(), numPartitions)
      }
    }

    private def nonNegativeMod(x: Int, mod: Int): Int = {
      val rawMod = x % mod
      rawMod + (if (rawMod < 0) mod else 0)
    }
  }
}
