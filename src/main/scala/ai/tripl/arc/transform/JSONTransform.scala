package ai.tripl.arc.transform

import java.lang._
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
import ai.tripl.arc.util.MetadataUtils
import ai.tripl.arc.util.ListenerUtils
import ai.tripl.arc.util.Utils

class JSONTransform extends PipelineStagePlugin {

  val version = Utils.getFrameworkVersion

  def createStage(index: Int, config: com.typesafe.config.Config)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Either[List[ai.tripl.arc.config.Error.StageError], PipelineStage] = {
    import ai.tripl.arc.config.ConfigReader._
    import ai.tripl.arc.config.ConfigUtils._
    implicit val c = config

    val expectedKeys = "type" :: "name" :: "description" :: "environments" :: "inputView" :: "outputView" :: "persist" :: "params" :: "numPartitions" :: "partitionBy" :: Nil
    val name = getValue[String]("name")
    val description = getOptionalValue[String]("description")
    val inputView = getValue[String]("inputView")
    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist", default = Some(false))
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val partitionBy = getValue[StringList]("partitionBy", default = Some(Nil))  
    val params = readMap("params", c)
    val invalidKeys = checkValidKeys(c)(expectedKeys)  

    (name, description, inputView, outputView, persist, numPartitions, partitionBy, invalidKeys) match {
      case (Right(name), Right(description), Right(inputView), Right(outputView), Right(persist), Right(numPartitions), Right(partitionBy), Right(invalidKeys)) => 

      val stage = JSONTransformStage(
          plugin=this,
          name=name,
          description=description,
          inputView=inputView,
          outputView=outputView,
          params=params,
          persist=persist,
          numPartitions=numPartitions,
          partitionBy=partitionBy
        )

        stage.stageDetail.put("inputView", inputView)  
        stage.stageDetail.put("outputView", outputView)   
        stage.stageDetail.put("persist", Boolean.valueOf(persist))        

        Right(stage)
      case _ =>
        val allErrors: Errors = List(name, description, inputView, outputView, persist, invalidKeys, numPartitions, partitionBy).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(index, stageName, c.origin.lineNumber, allErrors)
        Left(err :: Nil)
    }
  }
}

case class JSONTransformStage(
    plugin: PipelineStagePlugin,
    name: String, 
    description: Option[String], 
    inputView: String, 
    outputView: String, 
    params: Map[String, String], 
    persist: Boolean, 
    numPartitions: Option[Int], 
    partitionBy: List[String]
  ) extends PipelineStage {

  override def execute()(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Option[DataFrame] = {
    JSONTransformStage.execute(this)
  }
}

object JSONTransformStage {

  def execute(stage: JSONTransformStage)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger): Option[DataFrame] = {

    val df = spark.table(stage.inputView)

    val dropMap = new java.util.HashMap[String, Object]()

    // JSON does not need to deal with NullType as it is silenty dropped on write but we want logging to be explicit
    val nulls = df.schema.filter( _.dataType == NullType).map(_.name)
    if (!nulls.isEmpty) {
      dropMap.put("NullType", nulls.asJava)
    }

    stage.stageDetail.put("drop", dropMap)   

    val transformedDF = try {
      df.toJSON.toDF
    } catch {
      case e: Exception => throw new Exception(e) with DetailException {
        override val detail = stage.stageDetail          
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
      stage.stageDetail.put("outputColumns", Integer.valueOf(repartitionedDF.schema.length))
      stage.stageDetail.put("numPartitions", Integer.valueOf(repartitionedDF.rdd.partitions.length))

      if (stage.persist) {
        repartitionedDF.persist(StorageLevel.MEMORY_AND_DISK_SER)
        stage.stageDetail.put("records", Long.valueOf(repartitionedDF.count)) 
      }      
    }

    Option(repartitionedDF)
  }

}
