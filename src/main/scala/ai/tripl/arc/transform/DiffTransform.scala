package ai.tripl.arc.transform

import scala.collection.JavaConverters._

import org.apache.spark.sql._
import org.apache.spark.sql.functions._

import ai.tripl.arc.api.API._
import ai.tripl.arc.config._
import ai.tripl.arc.config.Error._
import ai.tripl.arc.plugins.PipelineStagePlugin
import ai.tripl.arc.util.Utils
import ai.tripl.arc.util.DataFrameUtils

class DiffTransform extends PipelineStagePlugin with JupyterCompleter {

  val version = Utils.getFrameworkVersion

  def snippet()(implicit arcContext: ARCContext): String = {
    s"""{
    |  "type": "DiffTransform",
    |  "name": "DiffTransform",
    |  "environments": [${arcContext.completionEnvironments.map { env => s""""${env}""""}.mkString(", ")}],
    |  "inputLeftView": "inputLeftView",
    |  "inputRightView": "inputRightView",
    |  "outputLeftView": "outputLeftView",
    |  "outputIntersectionView": "outputIntersectionView",
    |  "outputRightView": "outputRightView"
    |}""".stripMargin
  }

  val documentationURI = new java.net.URI(s"${baseURI}/transform/#difftransform")

  def instantiate(index: Int, config: com.typesafe.config.Config)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Either[List[ai.tripl.arc.config.Error.StageError], PipelineStage] = {
    import ai.tripl.arc.config.ConfigReader._
    import ai.tripl.arc.config.ConfigUtils._
    implicit val c = config

    val expectedKeys = "type" :: "id" :: "name" :: "description" :: "environments" :: "inputLeftView" :: "inputLeftKeys" :: "inputRightView" :: "inputRightKeys" :: "outputIntersectionView" :: "outputLeftView" :: "outputRightView" :: "persist" :: "params" :: Nil
    val id = getOptionalValue[String]("id")
    val name = getValue[String]("name")
    val description = getOptionalValue[String]("description")
    val inputLeftView = getValue[String]("inputLeftView")
    val inputRightView = getValue[String]("inputRightView")
    val inputLeftKeys = getValue[StringList]("inputLeftKeys", default = Some(Nil))
    val inputRightKeys = getValue[StringList]("inputRightKeys", default = Some(Nil))
    val outputIntersectionView = getOptionalValue[String]("outputIntersectionView")
    val outputLeftView = getOptionalValue[String]("outputLeftView")
    val outputRightView = getOptionalValue[String]("outputRightView")
    val persist = getValue[java.lang.Boolean]("persist", default = Some(false))
    val params = readMap("params", c)
    val invalidKeys = checkValidKeys(c)(expectedKeys)

    (id, name, description, inputLeftView, inputRightView, inputLeftKeys, inputRightKeys, outputIntersectionView, outputLeftView, outputRightView, persist, invalidKeys) match {
      case (Right(id), Right(name), Right(description), Right(inputLeftView), Right(inputRightView), Right(inputLeftKeys), Right(inputRightKeys), Right(outputIntersectionView), Right(outputLeftView), Right(outputRightView), Right(persist), Right(invalidKeys)) =>

        val stage = DiffTransformStage(
          plugin=this,
          id=id,
          name=name,
          description=description,
          inputLeftView=inputLeftView,
          inputRightView=inputRightView,
          inputLeftKeys=inputLeftKeys,
          inputRightKeys=inputRightKeys,
          outputIntersectionView=outputIntersectionView,
          outputLeftView=outputLeftView,
          outputRightView=outputRightView,
          params=params,
          persist=persist
        )

        outputIntersectionView.foreach { stage.stageDetail.put("outputIntersectionView", _)}
        outputLeftView.foreach { stage.stageDetail.put("outputLeftView", _)}
        outputRightView.foreach { stage.stageDetail.put("outputRightView", _)}
        stage.stageDetail.put("inputLeftKeys", if (inputLeftKeys.isEmpty) Seq("*").asJava else inputLeftKeys.asJava)
        stage.stageDetail.put("inputRightKeys", if (inputRightKeys.isEmpty) Seq("*").asJava else inputRightKeys.asJava)
        stage.stageDetail.put("inputLeftView", inputLeftView)
        stage.stageDetail.put("inputRightView", inputRightView)
        stage.stageDetail.put("params", params.asJava)
        stage.stageDetail.put("persist", java.lang.Boolean.valueOf(persist))

        Right(stage)
      case _ =>
        val allErrors: Errors = List(id, name, description, inputLeftView, inputRightView, outputIntersectionView, outputLeftView, outputRightView, persist, inputLeftKeys, inputRightKeys, invalidKeys).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(index, stageName, c.origin.lineNumber, allErrors)
        Left(err :: Nil)
    }
  }
}


case class DiffTransformStage(
    plugin: DiffTransform,
    id: Option[String],
    name: String,
    description: Option[String],
    inputLeftView: String,
    inputRightView: String,
    inputLeftKeys: List[String],
    inputRightKeys: List[String],
    outputIntersectionView: Option[String],
    outputLeftView: Option[String],
    outputRightView: Option[String],
    params: Map[String, String],
    persist: Boolean
  ) extends PipelineStage {

  override def execute()(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Option[DataFrame] = {
    DiffTransformStage.execute(this)
  }

}

object DiffTransformStage {

  val HASH_KEY = "__hash__"

  def execute(stage: DiffTransformStage)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Option[DataFrame] = {

    val inputLeftDF = spark.table(stage.inputLeftView)
    val inputRightDF = spark.table(stage.inputRightView)

    // do a full join on a calculated hash of all values in row on each dataset
    // trying to calculate the hash value inside the joinWith method produced an inconsistent result
    val hasLeftKeys = stage.inputLeftKeys.size != 0
    val leftKeys = if (hasLeftKeys) stage.inputLeftKeys.toArray.map(col _) else inputLeftDF.columns.map(col _)
    val leftHashDF = inputLeftDF.withColumn(HASH_KEY, sha2(to_json(struct(leftKeys:_*)), 512))
    val hasRightKeys = stage.inputRightKeys.size != 0
    val rightKeys = if (hasRightKeys) stage.inputRightKeys.toArray.map(col _) else inputRightDF.columns.map(col _)
    val rightHashDF = inputRightDF.withColumn(HASH_KEY, sha2(to_json(struct(rightKeys:_*)), 512))
    val transformedDF = leftHashDF.joinWith(rightHashDF, leftHashDF(HASH_KEY) === rightHashDF(HASH_KEY), "full")

    val outputIntersectionDF = if (hasLeftKeys || hasRightKeys) {
      // join and drop hash keys
      DataFrameUtils.dropFrom(
        DataFrameUtils.dropFrom(
          transformedDF.filter(col("_1").isNotNull).filter(col("_2").isNotNull).withColumnRenamed("_1", "left").withColumnRenamed("_2", "right")
        , "left", HASH_KEY :: Nil)
      ,"right", HASH_KEY :: Nil)
    } else {
      transformedDF.filter(col("_1").isNotNull).filter(col("_2").isNotNull).select(col("_1.*")).drop(HASH_KEY)
    }
    val outputLeftDF = transformedDF.filter(col("_2").isNull).select(col("_1.*")).drop(HASH_KEY)
    val outputRightDF = transformedDF.filter(col("_1").isNull).select(col("_2.*")).drop(HASH_KEY)

    if (stage.persist && !transformedDF.isStreaming) {
      transformedDF.persist(arcContext.storageLevel)
      val recordsMap = new java.util.HashMap[String, Object]()
      recordsMap.put("intersection", java.lang.Long.valueOf(outputIntersectionDF.count))
      recordsMap.put("left", java.lang.Long.valueOf(outputLeftDF.count))
      recordsMap.put("right", java.lang.Long.valueOf(outputRightDF.count))
      stage.stageDetail.put("records", recordsMap)
    }

    // register views
    for (outputIntersectionView <- stage.outputIntersectionView) {
      if (arcContext.immutableViews) outputIntersectionDF.createTempView(outputIntersectionView) else outputIntersectionDF.createOrReplaceTempView(outputIntersectionView)
    }
    for (outputLeftView <- stage.outputLeftView) {
      if (arcContext.immutableViews) outputLeftDF.createTempView(outputLeftView) else outputLeftDF.createOrReplaceTempView(outputLeftView)
    }
    for (outputRightView <- stage.outputRightView) {
      if (arcContext.immutableViews) outputRightDF.createTempView(outputRightView) else outputRightDF.createOrReplaceTempView(outputRightView)
    }

    Option(outputIntersectionDF)
  }

}
