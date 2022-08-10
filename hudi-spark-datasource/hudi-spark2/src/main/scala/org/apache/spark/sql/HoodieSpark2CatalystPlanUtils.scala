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

package org.apache.spark.sql

import org.apache.spark.sql.catalyst.{AliasIdentifier, TableIdentifier}
import org.apache.spark.sql.catalyst.analysis.{SimpleAnalyzer, UnresolvedRelation}
import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression, Like}
import org.apache.spark.sql.catalyst.optimizer.SimplifyCasts
import org.apache.spark.sql.catalyst.plans.JoinType
import org.apache.spark.sql.catalyst.plans.logical.{InsertIntoTable, Join, LogicalPlan}
import org.apache.spark.sql.execution.command.{AlterTableRecoverPartitionsCommand, ExplainCommand}
import org.apache.spark.sql.internal.SQLConf

object HoodieSpark2CatalystPlanUtils extends HoodieCatalystPlansUtils {

  def resolveOutputColumns(tableName: String,
                           expected: Seq[Attribute],
                           query: LogicalPlan,
                           byName: Boolean,
                           conf: SQLConf): LogicalPlan = {
    // NOTE: We have to apply [[ResolveUpCast]] and [[SimplifyCasts]] rules since by default Spark 2.x will
    //       always be wrapping matched attributes into [[UpCast]]s which aren't resolvable and render some
    //       APIs like [[QueryPlan.schema]] unusable
    SimplifyCasts.apply(
      SimpleAnalyzer.ResolveUpCast.apply(
        SimpleAnalyzer.ResolveOutputRelation.resolveOutputColumns(tableName, expected, query, byName)))
  }

  def createExplainCommand(plan: LogicalPlan, extended: Boolean): LogicalPlan =
    ExplainCommand(plan, extended = extended)

  override def toTableIdentifier(aliasId: AliasIdentifier): TableIdentifier = {
    TableIdentifier(aliasId.identifier, aliasId.database)
  }

  override def createJoin(left: LogicalPlan, right: LogicalPlan, joinType: JoinType): Join = {
    Join(left, right, joinType, None)
  }

  override def isInsertInto(plan: LogicalPlan): Boolean = {
    plan.isInstanceOf[InsertIntoTable]
  }

  override def getInsertIntoChildren(plan: LogicalPlan):
  Option[(LogicalPlan, Map[String, Option[String]], LogicalPlan, Boolean, Boolean)] = {
    plan match {
      case InsertIntoTable(table, partition, query, overwrite, ifPartitionNotExists) =>
        Some((table, partition, query, overwrite, ifPartitionNotExists))
      case _=> None
    }
  }

  override def createInsertInto(table: LogicalPlan, partition: Map[String, Option[String]],
                                query: LogicalPlan, overwrite: Boolean, ifPartitionNotExists: Boolean): LogicalPlan = {
    InsertIntoTable(table, partition, query, overwrite, ifPartitionNotExists)
  }

  override def createLike(left: Expression, right: Expression): Expression = {
    Like(left, right)
  }

  override def isRepairTable(plan: LogicalPlan): Boolean = {
    plan.isInstanceOf[AlterTableRecoverPartitionsCommand]
  }

  override def getRepairTableChildren(plan: LogicalPlan): Option[(TableIdentifier, Boolean, Boolean, String)] = {
    plan match {
      // For Spark >= 3.2.x, AlterTableRecoverPartitionsCommand was renamed RepairTableCommand, and added two new
      // parameters: enableAddPartitions and enableDropPartitions. By setting them to true and false, can restore
      // AlterTableRecoverPartitionsCommand's behavior
      case c: AlterTableRecoverPartitionsCommand =>
        Some((c.tableName, true, false, c.cmd))
    }
  }
}
