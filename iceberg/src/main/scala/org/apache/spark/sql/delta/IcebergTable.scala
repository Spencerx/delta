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

package org.apache.spark.sql.delta.commands.convert

import java.util.Locale

import scala.collection.JavaConverters._

import org.apache.spark.sql.delta.{DeltaColumnMapping, DeltaColumnMappingMode, DeltaConfigs, IdMapping, SerializableFileStatus, Snapshot}
import org.apache.spark.sql.delta.DeltaErrors.{cloneFromIcebergSourceWithoutSpecs, cloneFromIcebergSourceWithPartitionEvolution}
import org.apache.spark.sql.delta.schema.SchemaMergingUtils
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.iceberg.{PartitionSpec, Schema, Snapshot => IcebergSnapshot, Table, TableProperties}
import org.apache.iceberg.hadoop.HadoopTables
import org.apache.iceberg.io.FileIO
import org.apache.iceberg.transforms.{Bucket, IcebergPartitionUtil}
import org.apache.iceberg.util.PropertyUtil

import org.apache.spark.sql.{AnalysisException, SparkSession}
import org.apache.spark.sql.execution.datasources.PartitioningUtils
import org.apache.spark.sql.types.StructType

/** Subset of [[Table]] functionality required for conversion to Delta. */
trait IcebergTableLike {
  def location(): String
  def schema(): Schema
  def properties(): java.util.Map[String, String]
  def specs(): java.util.Map[Integer, PartitionSpec]
  def spec(): PartitionSpec
  def currentSnapshot(): IcebergSnapshot
  def snapshot(id: Long): IcebergSnapshot
  def io(): FileIO
}

/**
 * Implementation of [[IcebergTableLike]] that can safely rely on the functionality of an
 * underlying [[Table]].
 */
case class DelegatingIcebergTable(table: Table) extends IcebergTableLike {
  override def location(): String = table.location()
  override def schema(): Schema = table.schema()
  override def properties(): java.util.Map[String, String] = table.properties()
  override def specs(): java.util.Map[Integer, PartitionSpec] = table.specs()
  override def spec(): PartitionSpec = table.spec()
  override def currentSnapshot(): IcebergSnapshot = table.currentSnapshot()
  override def snapshot(id: Long): IcebergSnapshot = table.snapshot(id)
  override def io(): FileIO = table.io()
}

/**
 * A target Iceberg table for conversion to a Delta table.
 *
 * @param icebergTable the Iceberg table underneath.
 * @param deltaSnapshot the delta snapshot used for incremental update, none for initial conversion.
 * @param convertStats flag for disabling convert iceberg stats directly into Delta stats.
 *                     If you wonder why we need this flag, you are not alone.
 *                     This flag is only used by the old, obsolete, legacy command
 *                     `CONVERT TO DELTA NO STATISTICS`.
 *                     We believe that back then the CONVERT command suffered performance
 *                     problem due to stats collection and design `NO STATISTICS` as a workaround.
 *                     Now we are able to generate stats much faster, but when this flag is true,
 *                     we still have to honor it and give up generating stats. What a pity!
 */
class IcebergTable(
    spark: SparkSession,
    icebergTable: IcebergTableLike,
    deltaSnapshot: Option[Snapshot],
    convertStats: Boolean) extends ConvertTargetTable {
  def this(
      spark: SparkSession,
      table: Table,
      deltaSnapshot: Option[Snapshot],
      convertStats: Boolean) =
    this(spark, DelegatingIcebergTable(table), deltaSnapshot, convertStats)

  def this(spark: SparkSession, basePath: String, deltaTable: Option[Snapshot],
           convertStats: Boolean = true) =
    // scalastyle:off deltahadoopconfiguration
    this(
      spark,
      DelegatingIcebergTable(new HadoopTables(spark.sessionState.newHadoopConf).load(basePath)),
      deltaTable,
      convertStats)
    // scalastyle:on deltahadoopconfiguration

  protected val existingSchema: Option[StructType] = deltaSnapshot.map(_.schema)

  private val partitionEvolutionEnabled =
    spark.sessionState.conf.getConf(DeltaSQLConf.DELTA_CONVERT_ICEBERG_PARTITION_EVOLUTION_ENABLED)

  private val bucketPartitionEnabled =
    spark.sessionState.conf.getConf(DeltaSQLConf.DELTA_CONVERT_ICEBERG_BUCKET_PARTITION_ENABLED) ||
      deltaSnapshot.exists(s =>
        DeltaConfigs.IGNORE_ICEBERG_BUCKET_PARTITION.fromMetaData(s.metadata)
      )

  // When a table is CLONED/federated with the session conf ON, it will have the table property
  // set and will continue to support CAST TIME TYPE even when later the session conf is OFF.
  private val castTimeType =
    spark.sessionState.conf.getConf(DeltaSQLConf.DELTA_CONVERT_ICEBERG_CAST_TIME_TYPE) ||
      deltaSnapshot.exists(s => DeltaConfigs.CAST_ICEBERG_TIME_TYPE.fromMetaData(s.metadata))

  protected val fieldPathToPhysicalName: Map[Seq[String], String] =
    existingSchema.map {
      SchemaMergingUtils.explode(_).collect {
        case (path, field) if DeltaColumnMapping.hasPhysicalName(field) =>
          path.map(_.toLowerCase(Locale.ROOT)) -> DeltaColumnMapping.getPhysicalName(field)
      }.toMap
    }.getOrElse(Map.empty[Seq[String], String])

  private val convertedSchema = {
    // Reuse physical names of existing columns.
    val mergedSchema = DeltaColumnMapping.setPhysicalNames(
      IcebergSchemaUtils.convertIcebergSchemaToSpark(icebergTable.schema(), castTimeType),
      fieldPathToPhysicalName)

    // Assign physical names to new columns.
    DeltaColumnMapping.assignPhysicalNames(mergedSchema, reuseLogicalName = true)
  }

  override val requiredColumnMappingMode: DeltaColumnMappingMode = IdMapping

  override val properties: Map[String, String] = {
    val maxSnapshotAgeMs = PropertyUtil.propertyAsLong(icebergTable.properties,
      TableProperties.MAX_SNAPSHOT_AGE_MS, TableProperties.MAX_SNAPSHOT_AGE_MS_DEFAULT)
    val castTimeTypeConf = if (castTimeType) {
      Some((DeltaConfigs.CAST_ICEBERG_TIME_TYPE.key -> "true"))
    } else {
      None
    }
    val bucketPartitionToNonPartition = if (bucketPartitionEnabled) {
      Some((DeltaConfigs.IGNORE_ICEBERG_BUCKET_PARTITION.key -> "true"))
    } else {
      None
    }
    icebergTable.properties().asScala.toMap + (DeltaConfigs.COLUMN_MAPPING_MODE.key -> "id") +
    (DeltaConfigs.LOG_RETENTION.key -> s"$maxSnapshotAgeMs millisecond") ++
      castTimeTypeConf ++
      bucketPartitionToNonPartition
  }

  val tablePartitionSpec: PartitionSpec = {
    // Validate && Get Partition Spec from Iceberg table
    // We don't support conversion from iceberg tables with partition evolution
    // So normally we only allow table having one partition spec
    //
    // However, we allow one special case where
    //  all data files have either no-partition or bucket-partition
    //  in this case we will convert them into non-partition, so
    //  we will use an arbitrary non-bucket-partition spec as table's spec
    if (icebergTable.specs().size() == 1 || partitionEvolutionEnabled || !bucketPartitionEnabled) {
      icebergTable.spec()
    } else if (icebergTable.specs().isEmpty) {
      throw cloneFromIcebergSourceWithoutSpecs()
    } else {
      icebergTable.specs().asScala.values.find(
        !IcebergPartitionUtil.hasNonBucketPartition(_)
      ).getOrElse {
        throw cloneFromIcebergSourceWithPartitionEvolution()
      }
    }
  }

  override val partitionSchema: StructType = {
    // Reuse physical names of existing columns.
    val mergedPartitionSchema = DeltaColumnMapping.setPhysicalNames(
      StructType(
        IcebergPartitionUtil.getPartitionFields(tablePartitionSpec, icebergTable.schema())),
      fieldPathToPhysicalName)

    // Assign physical names to new partition columns.
    DeltaColumnMapping.assignPhysicalNames(mergedPartitionSchema, reuseLogicalName = true)
  }

  val tableSchema: StructType = PartitioningUtils.mergeDataAndPartitionSchema(
    convertedSchema,
    partitionSchema,
    spark.sessionState.conf.caseSensitiveAnalysis)._1

  checkConvertible()

  val fileManifest = new IcebergFileManifest(spark, icebergTable, partitionSchema, convertStats)

  lazy val numFiles: Long =
    Option(icebergTable.currentSnapshot())
      .flatMap { snapshot =>
        Option(snapshot.summary()).flatMap(_.asScala.get("total-data-files").map(_.toLong))
      }
      .getOrElse(fileManifest.numFiles)

  lazy val sizeInBytes: Long =
    Option(icebergTable.currentSnapshot())
      .flatMap { snapshot =>
        Option(snapshot.summary()).flatMap(_.asScala.get("total-files-size").map(_.toLong))
      }
      .getOrElse(fileManifest.sizeInBytes)

  override val format: String = "iceberg"

  def checkConvertible(): Unit = {
    /**
     * If the sql conf bucketPartitionEnabled is true, then convert iceberg table with
     * bucket partition to unpartitioned delta table; if bucketPartitionEnabled is false,
     * block conversion.
     */
    if (!bucketPartitionEnabled && IcebergPartitionUtil.hasBucketPartition(icebergTable.spec())) {
      throw new UnsupportedOperationException(IcebergTable.ERR_BUCKET_PARTITION)
    }

    /**
     * Existing Iceberg Table that has data imported from table without field ids will need
     * to add a custom property to enable the mapping for Iceberg.
     * Therefore, we can simply check for the existence of this property to see if there was
     * a custom mapping within Iceberg.
     *
     * Ref: https://www.mail-archive.com/dev@iceberg.apache.org/msg01638.html
     */
    if (icebergTable.properties().containsKey(TableProperties.DEFAULT_NAME_MAPPING)) {
      throw new UnsupportedOperationException(IcebergTable.ERR_CUSTOM_NAME_MAPPING)
    }

    /**
     * Delta does not support case sensitive columns while Iceberg does. We should check for
     * this here to throw a better message tailored to converting to Delta than the default
     * AnalysisException
     */
     try {
       SchemaMergingUtils.checkColumnNameDuplication(tableSchema, "during convert to Delta")
     } catch {
       case e: AnalysisException if e.getMessage.contains("during convert to Delta") =>
         throw new UnsupportedOperationException(
           IcebergTable.caseSensitiveConversionExceptionMsg(e.getMessage))
     }
  }
}

object IcebergTable {
  /** Error message constants */
  val ERR_MULTIPLE_PARTITION_SPECS =
    s"Source iceberg table has undergone partition evolution"
  val ERR_CUSTOM_NAME_MAPPING = "Cannot convert Iceberg tables with column name mapping"

  val ERR_BUCKET_PARTITION = "Cannot convert Iceberg tables with bucket partition"

  def caseSensitiveConversionExceptionMsg(conflictingColumns: String): String =
    s"""Cannot convert table to Delta as the table contains column names that only differ by case.
       |$conflictingColumns. Delta does not support case sensitive column names.
       |Please rename these columns before converting to Delta.
       """.stripMargin
}
