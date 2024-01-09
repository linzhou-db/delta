/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta.commands

// scalastyle:off import.ordering.noEmptyLine
import org.apache.spark.sql.delta.skipping.clustering.temp.ClusterBySpec
import org.apache.spark.sql.delta.DeltaLog
import org.apache.spark.sql.delta.OptimisticTransaction
import org.apache.spark.sql.delta.actions.Action
import org.apache.spark.sql.delta.actions.AddCDCFile
import org.apache.spark.sql.delta.actions.AddFile
import org.apache.spark.sql.delta.constraints.Constraint
import org.apache.spark.sql.delta.constraints.Constraints.Check
import org.apache.spark.sql.delta.constraints.Invariants.ArbitraryExpression
import org.apache.spark.sql.delta.sources.DeltaSQLConf

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.types.StructType

/**
 * An interface for writing [[data]] into Delta tables.
 */
trait WriteIntoDeltaLike {
  /**
   * A helper method to create a new instances of [[WriteIntoDeltaLike]] with
   * updated [[configuration]].
   */
  def withNewWriterConfiguration(updatedConfiguration: Map[String, String]): WriteIntoDeltaLike

  /**
   * The configuration to be used for writing [[data]] into Delta table.
   */
  val configuration: Map[String, String]

  /**
   * Data to be written into Delta table.
   */
  val data: DataFrame

  /**
   * Write [[data]] into Delta table as part of [[txn]] and @return the actions to be committed.
   */
  def write(
      txn: OptimisticTransaction,
      sparkSession: SparkSession,
      clusterBySpecOpt: Option[ClusterBySpec] = None): Seq[Action]

  val deltaLog: DeltaLog



  /**
   * Replace where operationMetrics need to be recorded separately.
   * @param newFiles - AddFile and AddCDCFile added by write job
   * @param deleteActions - AddFile, RemoveFile, AddCDCFile added by Delete job
   */
  protected def registerReplaceWhereMetrics(
      spark: SparkSession,
      txn: OptimisticTransaction,
      newFiles: Seq[Action],
      deleteActions: Seq[Action]): Unit = {
    var numFiles = 0L
    var numCopiedRows = 0L
    var numOutputBytes = 0L
    var numNewRows = 0L
    var numAddedChangedFiles = 0L
    var hasRowLevelMetrics = true

    newFiles.foreach {
      case a: AddFile =>
        numFiles += 1
        numOutputBytes += a.size
        if (a.numLogicalRecords.isEmpty) {
          hasRowLevelMetrics = false
        } else {
          numNewRows += a.numLogicalRecords.get
        }
      case cdc: AddCDCFile =>
        numAddedChangedFiles += 1
      case _ =>
    }

    deleteActions.foreach {
      case a: AddFile =>
        numFiles += 1
        numOutputBytes += a.size
        if (a.numLogicalRecords.isEmpty) {
          hasRowLevelMetrics = false
        } else {
          numCopiedRows += a.numLogicalRecords.get
        }
      case _: AddCDCFile =>
        numAddedChangedFiles += 1
      // Remove metrics will be handled by the delete command.
      case _ =>
    }

    var sqlMetrics = Map(
      "numFiles" -> new SQLMetric("number of files written", numFiles),
      "numOutputBytes" -> new SQLMetric("number of output bytes", numOutputBytes),
      "numAddedChangeFiles" -> new SQLMetric(
        "number of change files added", numAddedChangedFiles)
    )
    if (hasRowLevelMetrics) {
      sqlMetrics ++= Map(
        "numOutputRows" -> new SQLMetric("number of rows added", numNewRows + numCopiedRows),
        "numCopiedRows" -> new SQLMetric("number of copied rows", numCopiedRows)
      )
    } else {
      // this will get filtered out in DeltaOperations.WRITE transformMetrics
      sqlMetrics ++= Map(
        "numOutputRows" -> new SQLMetric("number of rows added", 0L),
        "numCopiedRows" -> new SQLMetric("number of copied rows", 0L)
      )
    }
    txn.registerSQLMetrics(spark, sqlMetrics)
  }

  import org.apache.spark.sql.types.{ArrayType, CharType, DataType, MapType, VarcharType}
  protected def replaceCharWithVarchar(dt: DataType): DataType = dt match {
    case ArrayType(et, nullable) =>
      ArrayType(replaceCharWithVarchar(et), nullable)
    case MapType(kt, vt, nullable) =>
      MapType(replaceCharWithVarchar(kt), replaceCharWithVarchar(vt), nullable)
    case StructType(fields) =>
      StructType(fields.map { field =>
        field.copy(dataType = replaceCharWithVarchar(field.dataType))
      })
    case CharType(length) => VarcharType(length)
    case _ => dt
  }

  protected def extractConstraints(
      sparkSession: SparkSession,
      expr: Seq[Expression]): Seq[Constraint] = {
    if (!sparkSession.conf.get(DeltaSQLConf.REPLACEWHERE_CONSTRAINT_CHECK_ENABLED)) {
      Seq.empty
    } else {
      expr.flatMap { e =>
        // While writing out the new data, we only want to enforce constraint on expressions
        // with UnresolvedAttribute, that is, containing column name. Because we parse a
        // predicate string without analyzing it, if there's a column name, it has to be
        // unresolved.
        e.collectFirst {
          case _: UnresolvedAttribute =>
            val arbitraryExpression = ArbitraryExpression(e)
            Check(arbitraryExpression.name, arbitraryExpression.expression)
        }
      }
    }
  }
}
