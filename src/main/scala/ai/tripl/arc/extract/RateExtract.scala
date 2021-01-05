package ai.tripl.arc.extract

import scala.collection.JavaConverters._

import org.apache.spark.sql._

import ai.tripl.arc.api.API._
import ai.tripl.arc.config.Error._
import ai.tripl.arc.plugins.PipelineStagePlugin
import ai.tripl.arc.util.DetailException
import ai.tripl.arc.util.Utils

class RateExtract extends PipelineStagePlugin with JupyterCompleter {

  val version = Utils.getFrameworkVersion

  def snippet()(implicit arcContext: ARCContext): String = {
    s"""{
    |  "type": "RateExtract",
    |  "name": "RateExtract",
    |  "environments": [${arcContext.completionEnvironments.map { env => s""""${env}""""}.mkString(", ")}],
    |  "rowsPerSecond": 1,
    |  "rampUpTime": 0,
    |  "numPartitions": 10,
    |  "outputView": "outputView"
    |}""".stripMargin
  }

  val documentationURI = new java.net.URI(s"${baseURI}/extract/#rateextract")

  def instantiate(index: Int, config: com.typesafe.config.Config)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Either[List[ai.tripl.arc.config.Error.StageError], PipelineStage] = {
    import ai.tripl.arc.config.ConfigReader._
    import ai.tripl.arc.config.ConfigUtils._
    implicit val c = config

    val expectedKeys = "type" :: "id" :: "name" :: "description" :: "environments" :: "outputView" :: "rowsPerSecond" :: "rampUpTime" :: "numPartitions" :: "params" :: Nil
    val id = getOptionalValue[String]("id")
    val name = getValue[String]("name")
    val description = getOptionalValue[String]("description")
    val outputView = getValue[String]("outputView")
    val rowsPerSecond = getValue[Int]("rowsPerSecond", default = Some(1))
    val rampUpTime = getValue[Int]("rampUpTime", default = Some(1))
    val numPartitions = getValue[Int]("numPartitions", default = Some(spark.sparkContext.defaultParallelism))
    val params = readMap("params", c)
    val invalidKeys = checkValidKeys(c)(expectedKeys)

    (id, name, description, outputView, rowsPerSecond, rampUpTime, numPartitions, invalidKeys) match {
      case (Right(id), Right(name), Right(description), Right(outputView), Right(rowsPerSecond), Right(rampUpTime), Right(numPartitions), Right(invalidKeys)) =>

        val stage = RateExtractStage(
          plugin=this,
          id=id,
          name=name,
          description=description,
          outputView=outputView,
          rowsPerSecond=rowsPerSecond,
          rampUpTime=rampUpTime,
          numPartitions=numPartitions,
          params=params
        )

        stage.stageDetail.put("numPartitions", Integer.valueOf(numPartitions))
        stage.stageDetail.put("outputView", outputView)
        stage.stageDetail.put("params", params.asJava)
        stage.stageDetail.put("rampUpTime", Integer.valueOf(rampUpTime))
        stage.stageDetail.put("rowsPerSecond", Integer.valueOf(rowsPerSecond))

        Right(stage)
      case _ =>
        val allErrors: Errors = List(id, name, description, outputView, rowsPerSecond, rampUpTime, numPartitions, invalidKeys).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(index, stageName, c.origin.lineNumber, allErrors)
        Left(err :: Nil)
    }
  }

}

case class RateExtractStage(
    plugin: RateExtract,
    id: Option[String],
    name: String,
    description: Option[String],
    outputView: String,
    params: Map[String, String],
    rowsPerSecond: Int,
    rampUpTime: Int,
    numPartitions: Int
  ) extends ExtractPipelineStage {

  override def execute()(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Option[DataFrame] = {
    RateExtractStage.execute(this)
  }

}

object RateExtractStage {

  def execute(stage: RateExtractStage)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Option[DataFrame] = {

    if (!arcContext.isStreaming) {
      throw new Exception("RateExtract can only be executed in streaming mode.") with DetailException {
        override val detail = stage.stageDetail
      }
    }

    val df = spark.readStream
      .format("rate")
      .option("rowsPerSecond", stage.rowsPerSecond.toString)
      .option("rampUpTime", s"${stage.rampUpTime}s")
      .option("numPartitions", stage.numPartitions.toString)
      .load

    if (arcContext.immutableViews) df.createTempView(stage.outputView) else df.createOrReplaceTempView(stage.outputView)

    Option(df)
  }

}

