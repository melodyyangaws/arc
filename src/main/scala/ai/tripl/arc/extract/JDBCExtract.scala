package ai.tripl.arc.extract

import java.lang._
import java.net.URI
import java.sql.DriverManager
import java.util.Properties
import scala.collection.JavaConverters._

import org.apache.spark.sql._
import org.apache.spark.sql.types._
import org.apache.spark.storage.StorageLevel

import com.typesafe.config._

import ai.tripl.arc.api._
import ai.tripl.arc.api.API._
import ai.tripl.arc.config._
import ai.tripl.arc.config.Error._
import ai.tripl.arc.plugins.PipelineStagePlugin
import ai.tripl.arc.util.CloudUtils
import ai.tripl.arc.util.DetailException
import ai.tripl.arc.util.EitherUtils._
import ai.tripl.arc.util.ExtractUtils
import ai.tripl.arc.util.ControlUtils._
import ai.tripl.arc.util.MetadataUtils
import ai.tripl.arc.util.Utils

class JDBCExtract extends PipelineStagePlugin {

  val version = Utils.getFrameworkVersion

  def createStage(index: Int, config: com.typesafe.config.Config)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Either[List[ai.tripl.arc.config.Error.StageError], PipelineStage] = {
    import ai.tripl.arc.config.ConfigReader._
    import ai.tripl.arc.config.ConfigUtils._
    implicit val c = config

    val expectedKeys = "type" :: "name" :: "description" :: "environments" :: "jdbcURL" :: "tableName" :: "outputView" :: "authentication" :: "contiguousIndex" :: "fetchsize" :: "numPartitions" :: "params" :: "partitionBy" :: "partitionColumn" :: "persist" :: "predicates" :: "schemaURI" :: "schemaView" :: "params" :: Nil

    val name = getValue[String]("name")
    val description = getOptionalValue[String]("description")
    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist", default = Some(false))
    val jdbcURL = getValue[String]("jdbcURL")
    val driver = jdbcURL.rightFlatMap(uri => getJDBCDriver("jdbcURL", uri))
    val tableName = getValue[String]("tableName")
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val partitionBy = getValue[StringList]("partitionBy", default = Some(Nil))
    val fetchsize = getOptionalValue[Int]("fetchsize")
    val customSchema = getOptionalValue[String]("customSchema")
    val partitionColumn = getOptionalValue[String]("partitionColumn")
    val predicates = if (c.hasPath("predicates")) c.getStringList("predicates").asScala.toList else Nil
    val authentication = readAuthentication("authentication")
    val extractColumns = if(c.hasPath("schemaURI")) getValue[String]("schemaURI") |> parseURI("schemaURI") _ |> getExtractColumns("schemaURI", authentication) _ else Right(List.empty)
    val schemaView = if(c.hasPath("schemaView")) getValue[String]("schemaView") else Right("")
    val params = readMap("params", c)
    val invalidKeys = checkValidKeys(c)(expectedKeys)  

    (name, description, extractColumns, schemaView, outputView, persist, jdbcURL, driver, tableName, numPartitions, fetchsize, customSchema, partitionColumn, partitionBy, invalidKeys) match {
      case (Right(name), Right(description), Right(extractColumns), Right(schemaView), Right(outputView), Right(persist), Right(jdbcURL), Right(driver), Right(tableName), Right(numPartitions), Right(fetchsize), Right(customSchema), Right(partitionColumn), Right(partitionBy), Right(invalidKeys)) => 
        val schema = if(c.hasPath("schemaView")) Left(schemaView) else Right(extractColumns)

        val stage = JDBCExtractStage(
          plugin=this,
          name=name, 
          description=description,
          schema=schema,
          outputView=outputView, 
          jdbcURL=jdbcURL,
          driver=driver,
          tableName=tableName, 
          numPartitions=numPartitions, 
          partitionBy=partitionBy,
          fetchsize=fetchsize, 
          customSchema=customSchema,
          partitionColumn=partitionColumn,
          predicates=predicates,
          params=params, 
          persist=persist
        )

        stage.stageDetail.put("driver", driver.getClass.toString)  
        stage.stageDetail.put("jdbcURL", jdbcURL)
        stage.stageDetail.put("outputView", outputView)  
        stage.stageDetail.put("persist", Boolean.valueOf(persist))
        stage.stageDetail.put("tableName", tableName)
        for (partitionColumn <- partitionColumn) {
          stage.stageDetail.put("partitionColumn", partitionColumn)
        }
        predicates match {
          case Nil =>
          case predicates => stage.stageDetail.put("predicates", predicates.asJava)
        }
        for (fetchsize <- fetchsize) {
          stage.stageDetail.put("fetchsize", Integer.valueOf(fetchsize))
        }             

        Right(stage)
      case _ =>
        val allErrors: Errors = List(name, description, extractColumns, schemaView, outputView, persist, jdbcURL, driver, tableName, numPartitions, fetchsize, customSchema, partitionColumn, partitionBy, invalidKeys).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(index, stageName, c.origin.lineNumber, allErrors)
        Left(err :: Nil)
    }
  }

}

case class JDBCExtractStage(
    plugin: PipelineStagePlugin,
    name: String, 
    description: Option[String], 
    schema: Either[String, List[ExtractColumn]], 
    outputView: String, 
    jdbcURL: String, 
    tableName: String, 
    numPartitions: Option[Int], 
    fetchsize: Option[Int], 
    customSchema: Option[String], 
    driver: java.sql.Driver, 
    partitionColumn: Option[String], 
    params: Map[String, String], 
    persist: Boolean, 
    partitionBy: List[String], 
    predicates: List[String]
  ) extends PipelineStage {

  override def execute()(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Option[DataFrame] = {
    JDBCExtractStage.execute(this)
  }
}

object JDBCExtractStage {

  def execute(stage: JDBCExtractStage)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Option[DataFrame] = {

    // override defaults https://spark.apache.org/docs/latest/sql-programming-guide.html#jdbc-to-other-databases
    val connectionProperties = new Properties()
    connectionProperties.put("user", stage.params.get("user").getOrElse(""))
    connectionProperties.put("password", stage.params.get("password").getOrElse(""))

    for (numPartitions <- stage.numPartitions) {
      connectionProperties.put("numPartitions", numPartitions.toString)    
    }

    for (fetchsize <- stage.fetchsize) {
      connectionProperties.put("fetchsize", fetchsize.toString)    
    }     

    for (partitionColumn <- stage.partitionColumn) {
      connectionProperties.put("partitionColumn", partitionColumn)    

      // automatically set the lowerBound and upperBound
      try {
        using(DriverManager.getConnection(stage.jdbcURL, connectionProperties)) { connection =>
          using(connection.createStatement) { statement =>
            val res = statement.execute(s"SELECT MIN(${partitionColumn}), MAX(${partitionColumn}) FROM ${stage.tableName}")
            // try to get results to throw error if one exists
            if (res) {
              statement.getResultSet.next

              val lowerBound = statement.getResultSet.getLong(1)
              val upperBound = statement.getResultSet.getLong(2)

              connectionProperties.put("lowerBound", lowerBound.toString)    
              stage.stageDetail.put("lowerBound", Long.valueOf(lowerBound))
              connectionProperties.put("upperBound", upperBound.toString)    
              stage.stageDetail.put("upperBound", Long.valueOf(upperBound))
            }
          }
        }
      } catch {
        case e: Exception => throw new Exception(e) with DetailException {
          override val detail = stage.stageDetail          
        } 
      }
    }  

    // try to get the schema
    val optionSchema = try {
      ExtractUtils.getSchema(stage.schema)(spark, logger)
    } catch {
      case e: Exception => throw new Exception(e) with DetailException {
        override val detail = stage.stageDetail          
      }      
    }         

    val df = try {
      stage.predicates match {
        case Nil => spark.read.jdbc(stage.jdbcURL, stage.tableName, connectionProperties)
        case predicates => spark.read.jdbc(stage.jdbcURL, stage.tableName, predicates.toArray, connectionProperties)
      }
    } catch {
      case e: Exception => throw new Exception(e) with DetailException {
        override val detail = stage.stageDetail          
      }      
    }

    // set column metadata if exists
    val enrichedDF = optionSchema match {
        case Some(schema) => MetadataUtils.setMetadata(df, schema)
        case None => df   
    }    

    // repartition to distribute rows evenly
    val repartitionedDF = stage.partitionBy match {
      case Nil => { 
        stage.numPartitions match {
          case Some(numPartitions) => enrichedDF.repartition(numPartitions)
          case None => enrichedDF
        }   
      }
      case partitionBy => {
        // create a column array for repartitioning
        val partitionCols = partitionBy.map(col => df(col))
        stage.numPartitions match {
          case Some(numPartitions) => df.repartition(numPartitions, partitionCols:_*)
          case None => df.repartition(partitionCols:_*)
        }
      }
    } 
    repartitionedDF.createOrReplaceTempView(stage.outputView)
    
    stage.stageDetail.put("outputColumns", Integer.valueOf(repartitionedDF.schema.length))
    stage.stageDetail.put("numPartitions", Integer.valueOf(repartitionedDF.rdd.partitions.length))

    if (stage.persist) {
      repartitionedDF.persist(StorageLevel.MEMORY_AND_DISK_SER)
      stage.stageDetail.put("records", Long.valueOf(repartitionedDF.count)) 
    }    

    Option(repartitionedDF)
  }

}

