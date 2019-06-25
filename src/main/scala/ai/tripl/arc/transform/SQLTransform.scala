package ai.tripl.arc.transform

import java.lang._
import java.net.URI
import scala.collection.JavaConverters._

import org.apache.spark.sql._
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
import ai.tripl.arc.util.MetadataUtils
import ai.tripl.arc.util.ListenerUtils
import ai.tripl.arc.util.SQLUtils
import ai.tripl.arc.util.QueryExecutionUtils
import ai.tripl.arc.util.Utils

class SQLTransform extends PipelineStagePlugin {

  val version = Utils.getFrameworkVersion

  def createStage(index: Int, config: com.typesafe.config.Config)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Either[List[ai.tripl.arc.config.Error.StageError], PipelineStage] = {
    import ai.tripl.arc.config.ConfigReader._
    import ai.tripl.arc.config.ConfigUtils._
    implicit val c = config

    val expectedKeys = "type" :: "name" :: "description" :: "environments" :: "inputURI" :: "outputView" :: "authentication" :: "persist" :: "sqlParams" :: "params" :: "numPartitions" :: "partitionBy" :: Nil
    val name = getValue[String]("name")
    val description = getOptionalValue[String]("description")
    val inputURI = getValue[String]("inputURI") |> parseURI("inputURI") _
    val authentication = readAuthentication("authentication")  
    val inputSQL = inputURI.rightFlatMap{ uri => textContentForURI(uri, "inputURI", authentication) }
    val sqlParams = readMap("sqlParams", c)
    val validSQL = inputSQL |> injectSQLParams("inputURI", sqlParams, false) _ |> validateSQL("inputURI") _
    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist", default = Some(false))
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val partitionBy = getValue[StringList]("partitionBy", default = Some(Nil))        
    val params = readMap("params", c)
    val invalidKeys = checkValidKeys(c)(expectedKeys)  

    (name, description, inputURI, inputSQL, validSQL, outputView, persist, numPartitions, partitionBy, invalidKeys) match {
      case (Right(name), Right(description), Right(inputURI), Right(inputSQL), Right(validSQL), Right(outputView), Right(persist), Right(numPartitions), Right(partitionBy), Right(invalidKeys)) => 

        if (validSQL.toLowerCase() contains "now") {
          logger.warn()
            .field("event", "validateConfig")
            .field("name", name)
            .field("type", "SQLTransform")              
            .field("message", "sql contains NOW() function which may produce non-deterministic results")       
            .log()   
        } 

        if (validSQL.toLowerCase() contains "current_date") {
          logger.warn()
            .field("event", "validateConfig")
            .field("name", name)
            .field("type", "SQLTransform")              
            .field("message", "sql contains CURRENT_DATE() function which may produce non-deterministic results")       
            .log()   
        }

        if (validSQL.toLowerCase() contains "current_timestamp") {
          logger.warn()
            .field("event", "validateConfig")
            .field("name", name)
            .field("type", "SQLTransform")              
            .field("message", "sql contains CURRENT_TIMESTAMP() function which may produce non-deterministic results")       
            .log()   
        }        

        val stage = SQLTransformStage(
          plugin=this,
          name=name,
          description=description,
          inputURI=inputURI,
          sql=inputSQL,
          outputView=outputView,
          params=params,
          sqlParams=sqlParams,
          persist=persist,
          numPartitions=numPartitions,
          partitionBy=partitionBy
        )

        stage.stageDetail.put("inputURI", inputURI.toString)  
        stage.stageDetail.put("outputView", outputView)   
        stage.stageDetail.put("persist", Boolean.valueOf(persist))
        stage.stageDetail.put("sql", inputSQL)   
        stage.stageDetail.put("sqlParams", sqlParams.asJava)   

        Right(stage)
      case _ =>
        val allErrors: Errors = List(name, description, inputURI, inputSQL, validSQL, outputView, persist, numPartitions, partitionBy, invalidKeys).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(index, stageName, c.origin.lineNumber, allErrors)
        Left(err :: Nil)
    }
  }
}

case class SQLTransformStage(
    plugin: PipelineStagePlugin,
    name: String, 
    description: Option[String], 
    inputURI: URI, 
    sql: String, 
    outputView:String, 
    params: Map[String, String], 
    sqlParams: Map[String, String], 
    persist: Boolean, 
    numPartitions: Option[Int], 
    partitionBy: List[String]
  ) extends PipelineStage {

  override def execute()(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Option[DataFrame] = {
    SQLTransformStage.execute(this)
  }
}

object SQLTransformStage {
  
  def execute(stage: SQLTransformStage)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Option[DataFrame] = {
    val stageDetail = stage.stageDetail

    // inject sql parameters
    val stmt = SQLUtils.injectParameters(stage.sql, stage.sqlParams, false)
    stageDetail.put("sql", stmt)

    val transformedDF = try {
      spark.sql(stmt)
    } catch {
      case e: Exception => throw new Exception(e) with DetailException {
        override val detail = stageDetail          
      }
    }

    // repartition to distribute rows evenly
    val repartitionedDF = stage.partitionBy match {
      case Nil => { 
        stage.numPartitions match {
          case Some(numPartitions) => transformedDF.repartition(numPartitions)
          case None => transformedDF
        }   
      }
      case partitionBy => {
        // create a column array for repartitioning
        val partitionCols = partitionBy.map(col => transformedDF(col))
        stage.numPartitions match {
          case Some(numPartitions) => transformedDF.repartition(numPartitions, partitionCols:_*)
          case None => transformedDF.repartition(partitionCols:_*)
        }
      }
    }

    repartitionedDF.createOrReplaceTempView(stage.outputView)    

    if (!repartitionedDF.isStreaming) {
      // add partition and predicate pushdown detail to logs
      stageDetail.put("partitionFilters", QueryExecutionUtils.getPartitionFilters(repartitionedDF.queryExecution.executedPlan).toArray)
      stageDetail.put("dataFilters", QueryExecutionUtils.getDataFilters(repartitionedDF.queryExecution.executedPlan).toArray)
      stageDetail.put("outputColumns", Integer.valueOf(repartitionedDF.schema.length))
      stageDetail.put("numPartitions", Integer.valueOf(repartitionedDF.rdd.partitions.length))

      if (stage.persist) {
        repartitionedDF.persist(StorageLevel.MEMORY_AND_DISK_SER)
        stageDetail.put("records", Long.valueOf(repartitionedDF.count)) 
      }      
    }

    Option(repartitionedDF)
  }

}
