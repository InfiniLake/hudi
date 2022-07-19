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

package org.apache.spark.sql.hudi

import org.apache.hudi.SparkAdapterSupport
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.{FileSourceScanExec, ProjectExec, RowDataSourceScanExec, SparkPlan}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType}
import org.junit.jupiter.api.Assertions.assertEquals

class TestNestedSchemaPruningOptimization extends HoodieSparkSqlTestBase with SparkAdapterSupport {

  private def explain(df: DataFrame): String = {
    val explainCommand = sparkAdapter.getCatalystPlanUtils.createExplainCommand(df.queryExecution.logical, extended = true)
    executePlan(explainCommand)
      .executeCollect()
      .mkString("\n")
  }

  private def executePlan(plan: LogicalPlan): SparkPlan =
    spark.sessionState.executePlan(plan).executedPlan

  test("Test NestedSchemaPruning optimization (COW/MOR)") {
    withTempDir { tmp =>
      Seq("cow", "mor").foreach { tableType =>
        val tableName = generateTableName
        val tablePath = s"${tmp.getCanonicalPath}/$tableName"

        spark.sql(
          s"""
             |CREATE TABLE $tableName (
             |  id int,
             |  item STRUCT<name: string, price: double>,
             |  ts long
             |) USING HUDI TBLPROPERTIES (
             |  type = '$tableType',
             |  primaryKey = 'id',
             |  preCombineField = 'ts',
             |  hoodie.populate.meta.fields = 'false'
             |)
             |LOCATION '$tablePath'
           """.stripMargin)

        spark.sql(
          s"""
             |INSERT INTO $tableName
             |SELECT 1 AS id, named_struct('name', 'a1', 'price', 10) AS item, 123456 AS ts
        """.stripMargin)

        val selectDF = spark.sql(s"SELECT id, item.name FROM $tableName")

        val expectedSchema = StructType(Seq(
          StructField("id", IntegerType),
          StructField("item" , StructType(Seq(StructField("name", StringType))))
        ))

        spark.sessionState.conf.setConf(SQLConf.WHOLESTAGE_CODEGEN_ENABLED, false)

        val hint =
          """
            |Following is expected to be present in the plan (where ReadSchema has properly pruned nested structs, which
            |is an optimization performed by NestedSchemaPruning rule):
            |
            |== Physical Plan ==
            |*(1) Project [id#45, item#46.name AS name#55]
            |+- FileScan parquet default.h0[id#45,item#46] Batched: false, DataFilters: [], Format: Parquet, Location: HoodieFileIndex(1 paths)[file:/private/var/folders/kb/cnff55vj041g2nnlzs5ylqk00000gn/T/spark-7137..., PartitionFilters: [], PushedFilters: [], ReadSchema: struct<id:int,item:struct<name:string>>
            |]
            |""".stripMargin

        val executedPlan = executePlan(selectDF.logicalPlan)
        executedPlan match {
          // COW
          case ProjectExec(_, FileSourceScanExec(_, _, requiredSchema, _, _, _, _, tableIdentifier, _)) =>
            assertEquals(tableName, tableIdentifier.get.table)
            assertEquals(expectedSchema, requiredSchema, hint)

          // MOR
          case ProjectExec(_, RowDataSourceScanExec(_, requiredSchema, _, _, _, _, _, tableIdentifier)) =>
            assertEquals(tableName, tableIdentifier.get.table)
            assertEquals(expectedSchema, requiredSchema, hint)
        }
      }
    }
  }

}
