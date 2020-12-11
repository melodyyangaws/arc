package ai.tripl.arc.load

import java.sql.DriverManager
import java.util.Properties
import scala.collection.JavaConverters._

import org.apache.spark.sql._
import org.apache.spark.sql.types._
import org.apache.spark.sql.execution.datasources.jdbc._

import com.typesafe.config._

import ai.tripl.arc.api.API._
import ai.tripl.arc.config.Error._
import ai.tripl.arc.config._
import ai.tripl.arc.plugins.PipelineStagePlugin
import ai.tripl.arc.util.ControlUtils._
import ai.tripl.arc.util.DetailException
import ai.tripl.arc.util.EitherUtils._
import ai.tripl.arc.util.JDBCSink
import ai.tripl.arc.util.JDBCUtils
import ai.tripl.arc.util.ListenerUtils
import ai.tripl.arc.util.Utils

class JDBCLoad extends PipelineStagePlugin with JupyterCompleter {

  val version = Utils.getFrameworkVersion

  def snippet()(implicit arcContext: ARCContext): String = {
    s"""{
    |  "type": "JDBCLoad",
    |  "name": "JDBCLoad",
    |  "environments": [${arcContext.completionEnvironments.map { env => s""""${env}""""}.mkString(", ")}],
    |  "inputView": "inputView",
    |  "jdbcURL": "jdbc:postgresql://",
    |  "tableName": "database.schema.table"
    |}""".stripMargin
  }

  val documentationURI = new java.net.URI(s"${baseURI}/load/#jdbcload")

  def instantiate(index: Int, config: com.typesafe.config.Config)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Either[List[ai.tripl.arc.config.Error.StageError], PipelineStage] = {
    import ai.tripl.arc.config.ConfigReader._
    import ai.tripl.arc.config.ConfigUtils._
    implicit val c = config

    val expectedKeys = "type" :: "id" :: "name" :: "description" :: "environments" :: "inputView" :: "jdbcURL" :: "tableName" :: "params" :: "batchsize" :: "createTableColumnTypes" :: "createTableOptions" :: "isolationLevel" :: "numPartitions" :: "saveMode" :: "tablock" :: "truncate" :: Nil
    val id = getOptionalValue[String]("id")
    val name = getValue[String]("name")
    val description = getOptionalValue[String]("description")
    val inputView = getValue[String]("inputView")
    val jdbcURL = getValue[String]("jdbcURL")
    val driver = jdbcURL |> getJDBCDriver("jdbcURL") _
    val tableName = getValue[String]("tableName")
    val partitionBy = getValue[StringList]("partitionBy", default = Some(Nil))
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val isolationLevel = getValue[String]("isolationLevel", default = Some("READ_UNCOMMITTED"), validValues = "NONE" :: "READ_COMMITTED" :: "READ_UNCOMMITTED" :: "REPEATABLE_READ" :: "SERIALIZABLE" :: Nil) |> parseIsolationLevel("isolationLevel") _
    val batchsize = getValue[Int]("batchsize", default = Some(1000))
    val truncate = getValue[java.lang.Boolean]("truncate", default = Some(false))
    val createTableOptions = getOptionalValue[String]("createTableOptions")
    val createTableColumnTypes = getOptionalValue[String]("createTableColumnTypes")
    val saveMode = getValue[String]("saveMode", default = Some("Overwrite"), validValues = "Append" :: "ErrorIfExists" :: "Ignore" :: "Overwrite" :: Nil) |> parseSaveMode("saveMode") _
    val tablock = getValue[java.lang.Boolean]("tablock", default = Some(true))
    val params = readMap("params", c)
    val invalidKeys = checkValidKeys(c)(expectedKeys)

    (id, name, description, inputView, jdbcURL, driver, tableName, numPartitions, isolationLevel, batchsize, truncate, createTableOptions, createTableColumnTypes, saveMode, tablock, partitionBy, invalidKeys) match {
      case (Right(id), Right(name), Right(description), Right(inputView), Right(jdbcURL), Right(driver), Right(tableName), Right(numPartitions), Right(isolationLevel), Right(batchsize), Right(truncate), Right(createTableOptions), Right(createTableColumnTypes), Right(saveMode), Right(tablock), Right(partitionBy), Right(invalidKeys)) =>

        val stage = JDBCLoadStage(
          plugin=this,
          id=id,
          name=name,
          description=description,
          inputView=inputView,
          jdbcURL=jdbcURL,
          driver=driver,
          tableName=tableName,
          partitionBy=partitionBy,
          numPartitions=numPartitions,
          isolationLevel=isolationLevel,
          batchsize=batchsize,
          truncate=truncate,
          createTableOptions=createTableOptions,
          createTableColumnTypes=createTableColumnTypes,
          saveMode=saveMode,
          tablock=tablock,
          params=params
        )


        createTableColumnTypes.foreach { stage.stageDetail.put("createTableColumnTypes", _) }
        createTableOptions.foreach { stage.stageDetail.put("createTableOptions", _) }
        numPartitions.foreach { numPartitions => stage.stageDetail.put("numPartitions", Integer.valueOf(numPartitions)) }
        stage.stageDetail.put("batchsize", java.lang.Integer.valueOf(batchsize))
        stage.stageDetail.put("driver", driver.getClass.toString)
        stage.stageDetail.put("inputView", inputView)
        stage.stageDetail.put("isolationLevel", isolationLevel.sparkString)
        stage.stageDetail.put("jdbcURL", JDBCUtils.maskPassword(jdbcURL))
        stage.stageDetail.put("partitionBy", partitionBy.asJava)
        stage.stageDetail.put("saveMode", saveMode.toString.toLowerCase)
        stage.stageDetail.put("tableName", tableName)
        stage.stageDetail.put("tablock", java.lang.Boolean.valueOf(tablock))
        stage.stageDetail.put("truncate", java.lang.Boolean.valueOf(truncate))

        Right(stage)
      case _ =>
        val allErrors: Errors = List(id, name, description, inputView, jdbcURL, driver, tableName, numPartitions, isolationLevel, batchsize, truncate, createTableOptions, createTableColumnTypes, saveMode, tablock, partitionBy, invalidKeys).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(index, stageName, c.origin.lineNumber, allErrors)
        Left(err :: Nil)
    }
  }

  def parseIsolationLevel(path: String)(quote: String)(implicit c: Config): Either[Errors, IsolationLevel] = {
    quote.toLowerCase.trim match {
      case "none" => Right(IsolationLevel.None)
      case "read_committed" => Right(IsolationLevel.ReadCommitted)
      case "read_uncommitted" => Right(IsolationLevel.ReadUncommitted)
      case "repeatable_read" => Right(IsolationLevel.RepeatableRead)
      case "serializable" => Right(IsolationLevel.Serializable)
      case _ => Left(ConfigError(path, None, s"Invalid state. Please raise issue.") :: Nil)
    }
  }
}

case class JDBCLoadStage(
    plugin: JDBCLoad,
    id: Option[String],
    name: String,
    description: Option[String],
    inputView: String,
    jdbcURL: String,
    tableName: String,
    partitionBy: List[String],
    numPartitions: Option[Int],
    isolationLevel: IsolationLevel,
    batchsize: Int,
    truncate: Boolean,
    createTableOptions: Option[String],
    createTableColumnTypes: Option[String],
    saveMode: SaveMode,
    driver: java.sql.Driver,
    tablock: Boolean,
    params: Map[String, String]
  ) extends LoadPipelineStage {

  override def execute()(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Option[DataFrame] = {
    JDBCLoadStage.execute(this)
  }

}

object JDBCLoadStage {

  val SaveModeIgnore = -1

  def execute(stage: JDBCLoadStage)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Option[DataFrame] = {

    val df = spark.table(stage.inputView)

    // force cache the table so that when write verification is performed any upstream calculations are not executed twice
    if (!df.isStreaming && !spark.catalog.isCached(stage.inputView)) {
      df.cache
    }

    // override defaults https://spark.apache.org/docs/latest/sql-programming-guide.html#jdbc-to-other-databases
    // Properties is a Hashtable<Object,Object> but gets mapped to <String,String> so ensure all values are strings
    val connectionProperties = new Properties
    connectionProperties.put("dbtable", stage.tableName)
    for ((key, value) <- stage.params) {
      connectionProperties.put(key, value)
    }

    // build spark JDBCOptions object so we can utilise their inbuilt dialect support
    val jdbcOptions = new JdbcOptionsInWrite(Map("url"-> stage.jdbcURL, "dbtable" -> stage.tableName))

    // execute a count query on target db to get intial count
    val targetPreCount = try {
      using(DriverManager.getConnection(stage.jdbcURL, connectionProperties)) { connection =>
        // check if table exists
        if (JdbcUtils.tableExists(connection, jdbcOptions)) {
          stage.saveMode match {
            case SaveMode.ErrorIfExists => {
              throw new Exception(s"Table '${stage.tableName}' already exists and 'saveMode' equals 'ErrorIfExists' so cannot continue.")
            }
            case SaveMode.Ignore => {
              // return a constant if table exists and SaveMode.Ignore
              SaveModeIgnore
            }
            case SaveMode.Overwrite => {
              if (stage.truncate) {
                JdbcUtils.truncateTable(connection, jdbcOptions)
              } else {
                using(connection.createStatement) { statement =>
                  statement.executeUpdate(s"DELETE FROM ${stage.tableName}")
                }
              }
              0
            }
            case SaveMode.Append => {
              using(connection.createStatement) { statement =>
                val resultSet = statement.executeQuery(s"SELECT COUNT(*) AS count FROM ${stage.tableName}")
                resultSet.next
                resultSet.getInt("count")
              }
            }
          }
        } else {
          0
        }
      }
    } catch {
      case e: Exception => throw new Exception(e) with DetailException {
        override val detail = stage.stageDetail
      }
    }

    // JDBCLoad cannot handle a column of ArrayType
    val arrays = df.schema.filter( _.dataType.typeName == "array").map(_.name)
    // JDBCLoad cannot handle a column of NullType
    val nulls = df.schema.filter( _.dataType == NullType).map(_.name)
    val nonNullDF = if (!arrays.isEmpty || !nulls.isEmpty) {
      val dropMap = new java.util.HashMap[String, Object]()
      dropMap.put("ArrayType", arrays.asJava)
      dropMap.put("NullType", nulls.asJava)
      if (arcContext.dropUnsupported) {
        stage.stageDetail.put("drop", dropMap)
        df.drop(arrays:_*).drop(nulls:_*)
      } else {
        throw new Exception(s"""inputView '${stage.inputView}' contains types ${new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dropMap)} which are unsupported by JDBCLoad and 'dropUnsupported' is set to false.""")
      }
    } else {
      df
    }

    val listener = ListenerUtils.addStageCompletedListener(stage.stageDetail)

    // if not table exists and SaveMode.Ignore
    val outputDF = if (nonNullDF.isStreaming) {
      val jdbcSink = new JDBCSink(stage.jdbcURL, connectionProperties)
      stage.partitionBy match {
        case Nil => nonNullDF.writeStream.foreach(jdbcSink).start
        case partitionBy => {
          nonNullDF.writeStream.partitionBy(partitionBy:_*).foreach(jdbcSink).start
        }
      }
      None
    } else {
      if (targetPreCount != SaveModeIgnore) {
        val sourceCount = df.count
        stage.stageDetail.put("count", java.lang.Long.valueOf(sourceCount))

        val writtenDF =
          try {
            connectionProperties.put("truncate", stage.truncate.toString)
            connectionProperties.put("isolationLevel", stage.isolationLevel.sparkString)
            connectionProperties.put("batchsize", stage.batchsize.toString)

            for (numPartitions <- stage.numPartitions) {
              connectionProperties.put("numPartitions", numPartitions.toString)
            }
            for (createTableOptions <- stage.createTableOptions) {
              connectionProperties.put("createTableOptions", createTableOptions)
            }
            for (createTableColumnTypes <- stage.createTableColumnTypes) {
              connectionProperties.put("createTableColumnTypes", createTableColumnTypes)
            }

            stage.partitionBy match {
              case Nil => {
                nonNullDF.write.mode(stage.saveMode).jdbc(stage.jdbcURL, stage.tableName, connectionProperties)
              }
              case partitionBy => {
                nonNullDF.write.partitionBy(partitionBy:_*).mode(stage.saveMode).jdbc(stage.jdbcURL, stage.tableName, connectionProperties)
              }
            }

            // execute a count query on target db to ensure correct number of rows
            val targetPostCount = using(DriverManager.getConnection(stage.jdbcURL, connectionProperties)) { connection =>
              val resultSet = connection.createStatement.executeQuery(s"SELECT COUNT(*) AS count FROM ${stage.tableName}")
              resultSet.next
              resultSet.getInt("count")
            }

            // log counts
            stage.stageDetail.put("sourceCount", java.lang.Long.valueOf(sourceCount))
            stage.stageDetail.put("targetPreCount", java.lang.Long.valueOf(targetPreCount))
            stage.stageDetail.put("targetPostCount", java.lang.Long.valueOf(targetPostCount))

            if (sourceCount != targetPostCount - targetPreCount) {
              throw new Exception(s"JDBCLoad should create same number of records in the target ('${stage.tableName}') as exist in source ('${stage.inputView}') but source has ${sourceCount} records and target created ${targetPostCount-targetPreCount} records.")
            }

            nonNullDF
          } catch {
            case e: Exception => throw new Exception(e) with DetailException {
              override val detail = stage.stageDetail
            }
          }
        Option(writtenDF)
      } else {
        Option(df)
      }
    }

    spark.sparkContext.removeSparkListener(listener)

    outputDF
  }
}
