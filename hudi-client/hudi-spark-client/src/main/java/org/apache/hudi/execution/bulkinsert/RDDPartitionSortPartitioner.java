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

package org.apache.hudi.execution.bulkinsert;

import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.table.HoodieTableConfig;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.sql.HoodieJavaRDDUtils;
import scala.Tuple2;

import java.io.Serializable;
import java.util.Comparator;
import java.util.function.Function;

/**
 * A built-in partitioner that does local sorting for each RDD partition
 * after coalescing it to specified number of partitions.
 * Corresponds to the {@link BulkInsertSortMode#PARTITION_SORT} mode.
 *
 * @param <T> HoodieRecordPayload type
 */
public class RDDPartitionSortPartitioner<T extends HoodieRecordPayload>
    extends RepartitioningBulkInsertPartitionerBase<JavaRDD<HoodieRecord<T>>> {

  public RDDPartitionSortPartitioner(HoodieTableConfig tableConfig) {
    super(tableConfig);
  }

  @SuppressWarnings("unchecked")
  @Override
  public JavaRDD<HoodieRecord<T>> repartitionRecords(JavaRDD<HoodieRecord<T>> records,
                                                     int outputSparkPartitions) {

    // NOTE: Datasets being ingested into partitioned tables are additionally re-partitioned to better
    //       align dataset's logical partitioning with expected table's physical partitioning to
    //       provide for appropriate file-sizing and better control of the number of files created.
    //
    //       Please check out {@code GlobalSortPartitioner} java-doc for more details
    if (isPartitionedTable) {
      Comparator<Pair<String, String>> recordKeyComparator =
          Comparator.comparing((Function<Pair<String, String>, String> & Serializable) Pair::getValue);

      PartitionPathRDDPartitioner partitioner =
          new PartitionPathRDDPartitioner((pair) -> ((Pair<String, String>) pair).getKey(), outputSparkPartitions);

      // Both partition-path and record-key are extracted, since
      //    - Partition-path will be used for re-partitioning (as called out above)
      //    - Record-key will be used for sorting the records w/in individual partitions
      return records.mapToPair(record -> new Tuple2<>(Pair.of(record.getPartitionPath(), record.getRecordKey()), record))
          .repartitionAndSortWithinPartitions(partitioner, recordKeyComparator)
          .values();
    } else {
      JavaPairRDD<String, HoodieRecord<T>> kvPairsRDD =
          records.mapToPair(record -> new Tuple2<>(record.getRecordKey(), record));

      return HoodieJavaRDDUtils.sortWithinPartitions(kvPairsRDD, Comparator.naturalOrder()).values();
    }
  }

  @Override
  public boolean arePartitionRecordsSorted() {
    return true;
  }
}
