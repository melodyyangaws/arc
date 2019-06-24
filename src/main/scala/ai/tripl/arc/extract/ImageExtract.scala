package ai.tripl.arc.extract

import java.lang._
import java.net.URI
import scala.collection.JavaConverters._

import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.storage.StorageLevel
import org.apache.spark.ml.image.ImageSchema

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
import ai.tripl.arc.util.Utils

class ImageExtract extends PipelineStagePlugin {

  val version = Utils.getFrameworkVersion

  def createStage(index: Int, config: com.typesafe.config.Config)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Either[List[ai.tripl.arc.config.Error.StageError], PipelineStage] = {
    import ai.tripl.arc.config.ConfigReader._
    import ai.tripl.arc.config.ConfigUtils._
    implicit val c = config

    val expectedKeys = "type" :: "name" :: "description" :: "environments" :: "inputURI" :: "outputView" :: "authentication" :: "dropInvalid" :: "numPartitions" :: "partitionBy" :: "persist" :: "params" :: "basePath" :: Nil

    val name = getValue[String]("name")
    val description = getOptionalValue[String]("description")
    val inputURI = getValue[String]("inputURI")
    val parsedGlob = inputURI.rightFlatMap(glob => parseGlob("inputURI", glob))
    val outputView = getValue[String]("outputView")
    val persist = getValue[Boolean]("persist", default = Some(false))
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val partitionBy = if (c.hasPath("partitionBy")) c.getStringList("partitionBy").asScala.toList else Nil
    val authentication = readAuthentication("authentication")
    val dropInvalid = getValue[Boolean]("dropInvalid", default = Some(true))
    val basePath = getOptionalValue[String]("basePath")
    val params = readMap("params", c)
    val invalidKeys = checkValidKeys(c)(expectedKeys)    

    (name, description, inputURI, parsedGlob, outputView, persist, numPartitions, authentication, dropInvalid, basePath, invalidKeys) match {
      case (Right(name), Right(description), Right(inputURI), Right(parsedGlob), Right(outputView), Right(persist), Right(numPartitions), Right(authentication), Right(dropInvalid), Right(basePath), Right(invalidKeys)) => 

      val stage = ImageExtractStage(
        plugin=this,
        name=name,
        description=description,
        outputView=outputView,
        input=parsedGlob,
        authentication=authentication,
        params=params,
        persist=persist,
        numPartitions=numPartitions,
        partitionBy=partitionBy,
        basePath=basePath,
        dropInvalid=dropInvalid
      )

      for (bp <- basePath) {
        stage.stageDetail.put("basePath", bp)
      }
      stage.stageDetail.put("dropInvalid", Boolean.valueOf(dropInvalid))
      stage.stageDetail.put("input", parsedGlob)  
      stage.stageDetail.put("outputView", outputView)  
      stage.stageDetail.put("persist", Boolean.valueOf(persist))

      Right(stage)

      case _ =>
        val allErrors: Errors = List(name, description, inputURI, parsedGlob, outputView, persist, numPartitions, authentication, dropInvalid, basePath, invalidKeys).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(index, stageName, c.origin.lineNumber, allErrors)
        Left(err :: Nil)
    }
  }

}

case class ImageExtractStage(
    plugin: PipelineStagePlugin,
    name: String,
    description: Option[String], 
    outputView: String, 
    input: String, 
    authentication: Option[Authentication], 
    params: Map[String, String], 
    persist: Boolean, 
    numPartitions: Option[Int], 
    partitionBy: List[String], 
    dropInvalid: Boolean, 
    basePath: Option[String]
  ) extends PipelineStage {

  override def execute()(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Option[DataFrame] = {
    ImageExtractStage.execute(this)
  }
}

object ImageExtractStage {

  def execute(stage: ImageExtractStage)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Option[DataFrame] = {
    import spark.implicits._
    val stageDetail = stage.stageDetail

    CloudUtils.setHadoopConfiguration(stage.authentication)

    // if incoming dataset is empty create empty dataset with a known schema
    val df = try {
      if (arcContext.isStreaming) {
        spark.readStream.format("image").option("dropInvalid", stage.dropInvalid).schema(ImageSchema.imageSchema).load(stage.input)   
      } else {      
        stage.basePath match {
          case Some(basePath) => spark.read.format("image").option("dropInvalid", stage.dropInvalid).option("basePath", basePath).load(stage.input)
          case None => spark.read.format("image").option("dropInvalid", stage.dropInvalid).load(stage.input)  
        }
      }
    } catch {
      case e: AnalysisException if (e.getMessage.contains("Path does not exist")) => {
        spark.createDataFrame(spark.sparkContext.emptyRDD[Row], ImageSchema.imageSchema)
      }
      case e: Exception => throw new Exception(e) with DetailException {
        override val detail = stageDetail          
      }
    }    

    // repartition to distribute rows evenly
    val repartitionedDF = stage.partitionBy match {
      case Nil => { 
        stage.numPartitions match {
          case Some(numPartitions) => df.repartition(numPartitions)
          case None => df
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
    
    if (!repartitionedDF.isStreaming) {
      stageDetail.put("inputFiles", Integer.valueOf(repartitionedDF.inputFiles.length))
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