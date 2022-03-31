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

package org.apache.hudi.functional

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, LocatedFileStatus, Path}
import org.apache.hudi.DataSourceWriteOptions.{PRECOMBINE_FIELD, RECORDKEY_FIELD}
import org.apache.hudi.common.config.HoodieMetadataConfig
import org.apache.hudi.common.table.{HoodieTableConfig, HoodieTableMetaClient}
import org.apache.hudi.common.util.ParquetUtils
import org.apache.hudi.config.{HoodieStorageConfig, HoodieWriteConfig}
import org.apache.hudi.index.columnstats.ColumnStatsIndexHelper
import org.apache.hudi.metadata.HoodieTableMetadata
import org.apache.hudi.testutils.HoodieClientTestBase
import org.apache.hudi.{ColumnStatsIndexSupport, DataSourceWriteOptions}
import org.apache.spark.sql._
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.typedLit
import org.apache.spark.sql.types._
import org.junit.jupiter.api.Assertions.{assertEquals, assertNotNull, assertTrue}
import org.junit.jupiter.api._

import java.math.BigInteger
import java.sql.{Date, Timestamp}
import scala.collection.JavaConverters._
import scala.util.Random

@Tag("functional")
class TestColumnStatsIndex extends HoodieClientTestBase with ColumnStatsIndexSupport {
  var spark: SparkSession = _

  val sourceTableSchema =
    new StructType()
      .add("c1", IntegerType)
      .add("c2", StringType)
      .add("c3", DecimalType(9,3))
      .add("c4", TimestampType)
      .add("c5", ShortType)
      .add("c6", DateType)
      .add("c7", BinaryType)
      .add("c8", ByteType)

  @BeforeEach
  override def setUp() {
    initPath()
    initSparkContexts()
    initFileSystem()
    spark = sqlContext.sparkSession
  }

  @AfterEach
  override def tearDown() = {
    cleanupFileSystem()
    cleanupSparkContexts()
  }

  @Test
  def testMetadataColumnStatsIndex(): Unit = {
    setTableName("hoodie_test")
    initMetaClient()
    val sourceJSONTablePath = getClass.getClassLoader.getResource("index/zorder/input-table-json").toString
    val inputDF = spark.read
      .schema(sourceTableSchema)
      .json(sourceJSONTablePath)

    val opts = Map(
      "hoodie.insert.shuffle.parallelism" -> "4",
      "hoodie.upsert.shuffle.parallelism" -> "4",
      HoodieWriteConfig.TBL_NAME.key -> "hoodie_test",
      RECORDKEY_FIELD.key -> "c1",
      PRECOMBINE_FIELD.key -> "c1",
      HoodieMetadataConfig.ENABLE.key -> "true",
      HoodieMetadataConfig.ENABLE_METADATA_INDEX_COLUMN_STATS.key -> "true",
      HoodieMetadataConfig.ENABLE_METADATA_INDEX_COLUMN_STATS_FOR_ALL_COLUMNS.key -> "true",
      HoodieTableConfig.POPULATE_META_FIELDS.key -> "true"
    )

    inputDF.repartition(4)
      .write
      .format("hudi")
      .options(opts)
      .option(DataSourceWriteOptions.OPERATION.key, DataSourceWriteOptions.INSERT_OPERATION_OPT_VAL)
      .option(HoodieStorageConfig.PARQUET_MAX_FILE_SIZE.key, 100 * 1024)
      .mode(SaveMode.Overwrite)
      .save(basePath)

    metaClient = HoodieTableMetaClient.reload(metaClient)

    val metadataTablePath = HoodieTableMetadata.getMetadataTableBasePath(basePath)

    val targetDataTableColumns = sourceTableSchema.fields.map(f => (f.name, f.dataType))

    val transposedColStatsDF = transposeColumnStatsIndex(readColumnStatsIndex(spark, metadataTablePath), targetDataTableColumns)

    val expectedColStatsSchema = ColumnStatsIndexHelper.composeIndexSchema(sourceTableSchema.fields.toSeq.asJava)

    // Match against expected column stats table
    val expectedColStatsIndexTableDf =
      spark.read
        .schema(expectedColStatsSchema)
        .json(getClass.getClassLoader.getResource("index/zorder/z-index-table.json").toString)

    expectedColStatsIndexTableDf.schema.equals(transposedColStatsDF.schema)
    expectedColStatsIndexTableDf.collect().sameElements(transposedColStatsDF.collect())

    // do an upsert and validate
    val updateJSONTablePath = getClass.getClassLoader.getResource("index/zorder/another-input-table-json").toString
    val updateDF = spark.read
      .schema(sourceTableSchema)
      .json(updateJSONTablePath)

    updateDF.repartition(4)
      .write
      .format("hudi")
      .options(opts)
      .option(DataSourceWriteOptions.OPERATION.key, DataSourceWriteOptions.UPSERT_OPERATION_OPT_VAL)
      .option(HoodieStorageConfig.PARQUET_MAX_FILE_SIZE.key, 100 * 1024)
      .mode(SaveMode.Append)
      .save(basePath)

    metaClient = HoodieTableMetaClient.reload(metaClient)

    val transposedUpdatedColStatsDF = transposeColumnStatsIndex(readColumnStatsIndex(spark, metadataTablePath), targetDataTableColumns)

    val expectedColStatsIndexUpdatedDf =
      spark.read
        .schema(expectedColStatsSchema)
        .json(getClass.getClassLoader.getResource("index/zorder/update-column-stats-index-table.json").toString)

    expectedColStatsIndexUpdatedDf.schema.equals(transposedUpdatedColStatsDF.schema)
    expectedColStatsIndexUpdatedDf.collect().sameElements(transposedUpdatedColStatsDF.collect())
  }

  @Test
  def testParquetMetadataRangeExtraction(): Unit = {
    val df = generateRandomDataFrame(spark)

    val pathStr = tempDir.resolve("min-max").toAbsolutePath.toString

    df.write.format("parquet")
      .mode(SaveMode.Overwrite)
      .save(pathStr)

    val utils = new ParquetUtils

    val conf = new Configuration()
    val path = new Path(pathStr)
    val fs = path.getFileSystem(conf)

    val parquetFilePath = fs.listStatus(path).filter(fs => fs.getPath.getName.endsWith(".parquet")).toSeq.head.getPath

    val ranges = utils.readRangeFromParquetMetadata(conf, parquetFilePath,
      Seq("c1", "c2", "c3a", "c3b", "c3c", "c4", "c5", "c6", "c7", "c8").asJava)

    ranges.asScala.foreach(r => {
      // NOTE: Unfortunately Parquet can't compute statistics for Timestamp column, hence we
      //       skip it in our assertions
      if (r.getColumnName.equals("c4")) {
        // scalastyle:off return
        return
        // scalastyle:on return
      }

      val min = r.getMinValue
      val max = r.getMaxValue

      assertNotNull(min)
      assertNotNull(max)
      assertTrue(r.getMinValue.asInstanceOf[Comparable[Object]].compareTo(r.getMaxValue.asInstanceOf[Object]) <= 0)
    })
  }

  private def buildColumnStatsTableManually(tablePath: String, zorderedCols: Seq[String], indexSchema: StructType) = {
    val files = {
      val it = fs.listFiles(new Path(tablePath), true)
      var seq = Seq[LocatedFileStatus]()
      while (it.hasNext) {
        seq = seq :+ it.next()
      }
      seq
    }

    spark.createDataFrame(
      files.flatMap(file => {
        val df = spark.read.schema(sourceTableSchema).parquet(file.getPath.toString)
        val exprs: Seq[String] =
          s"'${typedLit(file.getPath.getName)}' AS file" +:
            df.columns
              .filter(col => zorderedCols.contains(col))
              .flatMap(col => {
                val minColName = s"${col}_minValue"
                val maxColName = s"${col}_maxValue"
                Seq(
                  s"min($col) AS $minColName",
                  s"max($col) AS $maxColName",
                  s"sum(cast(isnull($col) AS long)) AS ${col}_num_nulls"
                )
              })

        df.selectExpr(exprs: _*)
          .collect()
      }).asJava,
      indexSchema
    )
  }

  def bootstrapParquetInputTableFromJSON(sourceJSONTablePath: String, targetParquetTablePath: String): Unit = {
    val jsonInputDF =
    // NOTE: Schema here is provided for validation that the input date is in the appropriate format
      spark.read
        .schema(sourceTableSchema)
        .json(sourceJSONTablePath)

    jsonInputDF
      .sort("c1")
      .repartition(4, new Column("c1"))
      .write
      .format("parquet")
      .mode("overwrite")
      .save(targetParquetTablePath)

    val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
    // Have to cleanup additional artefacts of Spark write
    fs.delete(new Path(targetParquetTablePath, "_SUCCESS"), false)
  }

  def replace(ds: Dataset[Row]): DataFrame = {
    val uuidRegexp = "[a-z0-9]{8}-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{12}"

    val uuids =
      ds.selectExpr(s"regexp_extract(file, '(${uuidRegexp})')")
        .distinct()
        .collect()
        .map(_.getString(0))

    val uuidToIdx: UserDefinedFunction = functions.udf((fileName: String) => {
      val uuid = uuids.find(uuid => fileName.contains(uuid)).get
      fileName.replace(uuid, "xxx")
    })

    ds.withColumn("file", uuidToIdx(ds("file")))
  }

  private def generateRandomDataFrame(spark: SparkSession): DataFrame = {
    val sourceTableSchema =
      new StructType()
        .add("c1", IntegerType)
        .add("c2", StringType)
        // NOTE: We're testing different values for precision of the decimal to make sure
        //       we execute paths bearing different underlying representations in Parquet
        // REF: https://github.com/apache/parquet-format/blob/master/LogicalTypes.md#DECIMAL
        .add("c3a", DecimalType(9,3))
        .add("c3b", DecimalType(10,3))
        .add("c3c", DecimalType(20,3))
        .add("c4", TimestampType)
        .add("c5", ShortType)
        .add("c6", DateType)
        .add("c7", BinaryType)
        .add("c8", ByteType)

    val rdd = spark.sparkContext.parallelize(0 to 1000, 1).map { item =>
      val c1 = Integer.valueOf(item)
      val c2 = Random.nextString(10)
      val c3a = java.math.BigDecimal.valueOf(Random.nextInt() % (1 << 24), 3)
      val c3b = java.math.BigDecimal.valueOf(Random.nextLong() % (1L << 32), 3)
      // NOTE: We cap it at 2^64 to make sure we're not exceeding target decimal's range
      val c3c = new java.math.BigDecimal(new BigInteger(64, new java.util.Random()), 3)
      val c4 = new Timestamp(System.currentTimeMillis())
      val c5 = java.lang.Short.valueOf(s"${(item + 16) / 10}")
      val c6 = Date.valueOf(s"${2020}-${item % 11 + 1}-${item % 28 + 1}")
      val c7 = Array(item).map(_.toByte)
      val c8 = java.lang.Byte.valueOf("9")

      RowFactory.create(c1, c2, c3a, c3b, c3c, c4, c5, c6, c7, c8)
    }

    spark.createDataFrame(rdd, sourceTableSchema)
  }

  private def asJson(df: DataFrame) =
    df.toJSON
      .select("value")
      .collect()
      .toSeq
      .map(_.getString(0))
      .mkString("\n")

  private def sort(df: DataFrame): DataFrame = {
    val sortedCols = df.columns.sorted
    // Sort dataset by the first 2 columns (to minimize non-determinism in case multiple files have the same
    // value of the first column)
    df.select(sortedCols.head, sortedCols.tail: _*)
      .sort("c1_maxValue", "c1_minValue")
  }

}
