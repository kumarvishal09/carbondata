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
package org.apache.spark.sql.materializedview

import scala.collection.mutable.ListBuffer

import org.apache.spark.SparkConf
import org.apache.spark.sql.{CarbonDatasourceHadoopRelation, CarbonEnv, SparkSession}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.expressions.{Alias, AttributeReference, Cast}
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution.command.{ColumnTableRelation, Field}
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.hive.{CarbonRelation, CarbonSessionState}
import org.apache.spark.sql.hive.HiveExternalCatalog.{DATASOURCE_SCHEMA_NUMPARTS,
DATASOURCE_SCHEMA_PART_PREFIX}
import org.apache.spark.sql.types.DataType

import org.apache.carbondata.common.logging.{LogService, LogServiceFactory}
import org.apache.carbondata.core.constants.CarbonCommonConstants
import org.apache.carbondata.core.locks.{CarbonLockUtil, ICarbonLock, LockUsage}
import org.apache.carbondata.core.metadata.converter.ThriftWrapperSchemaConverterImpl
import org.apache.carbondata.core.metadata.schema.table.{CarbonTable, ChildSchema}
import org.apache.carbondata.core.util.path.CarbonStorePath
import org.apache.carbondata.format.TableInfo
import org.apache.carbondata.spark.exception.MalformedCarbonCommandException
import org.apache.carbondata.spark.util.CommonUtil

/**
 * Utility class for keeping all the utility method for pre-aggregate
 */
object PreAggregateUtil {

  private val LOGGER = LogServiceFactory.getLogService(this.getClass.getCanonicalName)

  def getParentCarbonTable(plan: LogicalPlan): CarbonTable = {
    plan match {
      case Aggregate(_, aExp, SubqueryAlias(_, l: LogicalRelation, _))
        if l.relation.isInstanceOf[CarbonDatasourceHadoopRelation] =>
        l.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation.metaData.carbonTable
      case _ => throw new MalformedCarbonCommandException("table does not exist")
    }
  }

  def validateActualSelectPlanAndGetAttrubites(plan: LogicalPlan,
      selectStmt: String): Seq[Field] = {
    val list = scala.collection.mutable.Set.empty[Field]
    plan match {
      case Aggregate(_, aExp, SubqueryAlias(_, l: LogicalRelation, _))
        if l.relation.isInstanceOf[CarbonDatasourceHadoopRelation] =>
        val carbonTable = l.relation.asInstanceOf[CarbonDatasourceHadoopRelation].carbonRelation
          .metaData.carbonTable
        val parentTableName = carbonTable.getAbsoluteTableIdentifier.getCarbonTableIdentifier
          .getTableName
        val parentDatabaseName = carbonTable.getAbsoluteTableIdentifier.getCarbonTableIdentifier
          .getDatabaseName
        if (!carbonTable.getTableInfo.getParentRelationIdentifiers.isEmpty) {
          throw new MalformedCarbonCommandException(
            "Pre Aggregation is not supported on Pre-Aggregated Table")
        }
        aExp.map {
          case Alias(attr: AggregateExpression, _) =>
            if (attr.isDistinct) {
              throw new MalformedCarbonCommandException(
                "Distinct is not supported On Pre Aggregation")
            }
            list ++= validateAggregateFunctionAndGetFields(carbonTable,
              attr.aggregateFunction,
              parentTableName,
              parentDatabaseName)
          case attr: AttributeReference =>
            list += getField(attr.name,
              attr.dataType,
              parentColumnId = carbonTable.getColumnByName(parentTableName, attr.name).getColumnId,
              parentTableName = parentTableName,
              parentDatabaseName = parentDatabaseName)
          case Alias(attr: AttributeReference, _) =>
            list += getField(attr.name,
              attr.dataType,
              parentColumnId = carbonTable.getColumnByName(parentTableName, attr.name).getColumnId,
              parentTableName = parentTableName,
              parentDatabaseName = parentDatabaseName)
          case _ =>
            throw new MalformedCarbonCommandException(s"Unsupported Select Statement:${
              selectStmt } ")
        }
        Some(carbonTable)
      case _ =>
        throw new MalformedCarbonCommandException(s"Unsupported Select Statement:${ selectStmt } ")
    }
    list.toSeq
  }

  def validateAggregateFunctionAndGetFields(carbonTable: CarbonTable,
      aggFunctions: AggregateFunction,
      parentTableName: String,
      parentDatabaseName: String) : scala.collection.mutable.Set[Field] = {
    val list = scala.collection.mutable.Set.empty[Field]
    aggFunctions match {
      case sum@Sum(attr: AttributeReference) =>
        list += getField(attr.name,
          attr.dataType,
          sum.prettyName,
          carbonTable.getColumnByName(parentTableName, attr.name).getColumnId,
          parentTableName,
          parentDatabaseName)
      case sum@Sum(Cast(attr: AttributeReference, changeDataType: DataType)) =>
        list += getField(attr.name,
          changeDataType,
          sum.prettyName,
          carbonTable.getColumnByName(parentTableName, attr.name).getColumnId,
          parentTableName,
          parentDatabaseName)
      case count@Count(Seq(attr: AttributeReference)) =>
        list += getField(attr.name,
          attr.dataType,
          count.prettyName,
          carbonTable.getColumnByName(parentTableName, attr.name).getColumnId,
          parentTableName,
          parentDatabaseName)
      case min@Min(attr: AttributeReference) =>
        list += getField(attr.name,
          attr.dataType,
          min.prettyName,
          carbonTable.getColumnByName(parentTableName, attr.name).getColumnId,
          parentTableName,
          parentDatabaseName)
      case min@Min(Cast(attr: AttributeReference, changeDataType: DataType)) =>
        list += getField(attr.name,
          changeDataType,
          min.prettyName,
          carbonTable.getColumnByName(parentTableName, attr.name).getColumnId,
          parentTableName,
          parentDatabaseName)
      case max@Max(attr: AttributeReference) =>
        list += getField(attr.name,
          attr.dataType,
          max.prettyName,
          carbonTable.getColumnByName(parentTableName, attr.name).getColumnId,
          parentTableName,
          parentDatabaseName)
      case max@Max(Cast(attr: AttributeReference, changeDataType: DataType)) =>
        list += getField(attr.name,
          changeDataType,
          max.prettyName,
          carbonTable.getColumnByName(parentTableName, attr.name).getColumnId,
          parentTableName,
          parentDatabaseName)
      case Average(attr: AttributeReference) =>
        list += getField(attr.name,
          attr.dataType,
          "sum",
          carbonTable.getColumnByName(parentTableName, attr.name).getColumnId,
          parentTableName,
          parentDatabaseName)
        list += getField(attr.name,
          attr.dataType,
          "count",
          carbonTable.getColumnByName(parentTableName, attr.name).getColumnId,
          parentTableName,
          parentDatabaseName)
      case Average(Cast(attr: AttributeReference, changeDataType: DataType)) =>
        list += getField(attr.name,
          changeDataType,
          "sum",
          carbonTable.getColumnByName(parentTableName, attr.name).getColumnId,
          parentTableName,
          parentDatabaseName)
        list += getField(attr.name,
          changeDataType,
          "count",
          carbonTable.getColumnByName(parentTableName, attr.name).getColumnId,
          parentTableName,
          parentDatabaseName)
      case _ =>
        throw new MalformedCarbonCommandException("Un-Supported Aggregation Type")
    }
    list
  }

  def getField(columnName: String,
      dataType: DataType,
      aggregateType: String = "",
      parentColumnId: String,
      parentTableName: String,
      parentDatabaseName: String): Field = {
    val actualColumnName = if (aggregateType.equals("")) {
      columnName
    } else {
      columnName + '_' + aggregateType
    }
    val rawSchema = '`' + actualColumnName + '`' + ' ' + dataType.typeName
    val columnTableRelation = ColumnTableRelation(parentColumnName = columnName,
      parentColumnId = parentColumnId,
      parentTableName = parentTableName,
      parentDatabaseName = parentDatabaseName)
    if (dataType.typeName.startsWith("decimal")) {
      val (precision, scale) = CommonUtil.getScaleAndPrecision(dataType.catalogString)
      Field(column = actualColumnName,
        dataType = Some(dataType.typeName),
        name = Some(actualColumnName),
        children = None,
        precision = precision,
        scale = scale,
        rawSchema = rawSchema,
        aggregateFunction = aggregateType,
        columnTableRelation = Some(columnTableRelation))
    }
    else {
      Field(column = actualColumnName,
        dataType = Some(dataType.typeName),
        name = Some(actualColumnName),
        children = None,
        rawSchema = rawSchema,
        aggregateFunction = aggregateType,
        columnTableRelation = Some(columnTableRelation))
    }
  }

  def updateMainTable(dbName: String, tableName: String,
      childSchema: ChildSchema, sparkSession: SparkSession): Unit = {
    val LOGGER: LogService = LogServiceFactory.getLogService(this.getClass.getCanonicalName)
    val locksToBeAcquired = List(LockUsage.METADATA_LOCK,
      LockUsage.DROP_TABLE_LOCK)
    var locks = List.empty[ICarbonLock]
    var carbonTable: CarbonTable = null
    var numberOfCurrentChild: Int = 0
    try {
      val metastore = CarbonEnv.getInstance(sparkSession).carbonMetastore
      carbonTable = metastore
        .lookupRelation(Some(dbName), tableName)(sparkSession).asInstanceOf[CarbonRelation]
        .tableMeta.carbonTable
      locks = acquireLock(dbName, tableName, locksToBeAcquired, carbonTable)
      // get the latest carbon table and check for column existence
      // read the latest schema file
      val carbonTablePath = CarbonStorePath.getCarbonTablePath(carbonTable.getStorePath,
        carbonTable.getCarbonTableIdentifier)
      val thriftTableInfo: TableInfo = metastore.getThriftTableInfo(carbonTablePath)(sparkSession)
      val schemaConverter = new ThriftWrapperSchemaConverterImpl()
      val wrapperTableInfo = schemaConverter
        .fromExternalToWrapperTableInfo(thriftTableInfo,
          dbName,
          tableName,
          carbonTable.getStorePath)
      numberOfCurrentChild = wrapperTableInfo.getChildSchemaList.size
      wrapperTableInfo.getChildSchemaList.add(childSchema)
      val thriftTable = schemaConverter
        .fromWrapperToExternalTableInfo(wrapperTableInfo, dbName, tableName)
      updateSchemaInfo(carbonTable,
        thriftTable)(sparkSession,
        sparkSession.sessionState.asInstanceOf[CarbonSessionState])
      LOGGER.info(s"Pre Aggeragte Parent table updated is successful for table $dbName.$tableName")
    } catch {
      case e: Exception =>
        LOGGER.error(e, "Pre Aggregate Parent table update failed reverting changes")
        revertMainTableChanges(dbName, tableName, numberOfCurrentChild)(sparkSession)
        throw e
    } finally {
      // release lock after command execution completion
      releaseLocks(locks)
    }
    Seq.empty
  }

  /**
   * @param carbonTable
   * @param thriftTable
   * @param sparkSession
   * @param sessionState
   */
  def updateSchemaInfo(carbonTable: CarbonTable,
      thriftTable: TableInfo)(sparkSession: SparkSession,
      sessionState: CarbonSessionState): Unit = {
    val dbName = carbonTable.getDatabaseName
    val tableName = carbonTable.getFactTableName
    CarbonEnv.getInstance(sparkSession).carbonMetastore
      .updateTableSchemaForPreAgg(carbonTable.getCarbonTableIdentifier,
        carbonTable.getCarbonTableIdentifier,
        thriftTable,
        carbonTable.getAbsoluteTableIdentifier.getTablePath)(sparkSession)
    val tableIdentifier = TableIdentifier(tableName, Some(dbName))
    sparkSession.catalog.refreshTable(tableIdentifier.quotedString)
  }

  /**
   * This method will split schema string into multiple parts of configured size and
   * registers the parts as keys in tableProperties which will be read by spark to prepare
   * Carbon Table fields
   *
   * @param sparkConf
   * @param schemaJsonString
   * @return
   */
  private def prepareSchemaJson(sparkConf: SparkConf,
      schemaJsonString: String): String = {
    val threshold = sparkConf
      .getInt(CarbonCommonConstants.SPARK_SCHEMA_STRING_LENGTH_THRESHOLD,
        CarbonCommonConstants.SPARK_SCHEMA_STRING_LENGTH_THRESHOLD_DEFAULT)
    // Split the JSON string.
    val parts = schemaJsonString.grouped(threshold).toSeq
    var schemaParts: Seq[String] = Seq.empty
    schemaParts = schemaParts :+ s"'$DATASOURCE_SCHEMA_NUMPARTS'='${ parts.size }'"
    parts.zipWithIndex.foreach { case (part, index) =>
      schemaParts = schemaParts :+ s"'$DATASOURCE_SCHEMA_PART_PREFIX$index'='$part'"
    }
    schemaParts.mkString(",")
  }

  /**
   * Validates that the table exists and acquires meta lock on it.
   *
   * @param dbName
   * @param tableName
   * @return
   */
  def acquireLock(dbName: String,
      tableName: String,
      locksToBeAcquired: List[String],
      table: CarbonTable): List[ICarbonLock] = {
    // acquire the lock first
    val acquiredLocks = ListBuffer[ICarbonLock]()
    try {
      locksToBeAcquired.foreach { lock =>
        acquiredLocks += CarbonLockUtil.getLockObject(table.getCarbonTableIdentifier, lock)
      }
      acquiredLocks.toList
    } catch {
      case e: Exception =>
        releaseLocks(acquiredLocks.toList)
        throw e
    }
  }

  /**
   * This method will release the locks acquired for an operation
   *
   * @param locks
   */
  def releaseLocks(locks: List[ICarbonLock]): Unit = {
    locks.foreach { carbonLock =>
      if (carbonLock.unlock()) {
        LOGGER.info("Pre agg table lock released successfully")
      } else {
        LOGGER.error("Unable to release lock during Pre agg table cretion")
      }
    }
  }

  /**
   * This method reverts the changes to the schema if add column command fails.
   *
   * @param dbName
   * @param tableName
   * @param numberOfChildSchema
   * @param sparkSession
   */
  def revertMainTableChanges(dbName: String, tableName: String, numberOfChildSchema: Int)
    (sparkSession: SparkSession): Unit = {
    val metastore = CarbonEnv.getInstance(sparkSession).carbonMetastore
    val carbonTable = metastore
      .lookupRelation(Some(dbName), tableName)(sparkSession).asInstanceOf[CarbonRelation].tableMeta
      .carbonTable
    carbonTable.getTableLastUpdatedTime
    val carbonTablePath = CarbonStorePath.getCarbonTablePath(carbonTable.getStorePath,
      carbonTable.getCarbonTableIdentifier)
    val thriftTable: TableInfo = metastore.getThriftTableInfo(carbonTablePath)(sparkSession)
    if (thriftTable.childSchemas.size > numberOfChildSchema) {
      metastore
        .revertTableSchemaForPreAggCreationFailure(carbonTable.getCarbonTableIdentifier,
          thriftTable, carbonTable.getAbsoluteTableIdentifier.getTablePath)(sparkSession)
    }
  }
}
