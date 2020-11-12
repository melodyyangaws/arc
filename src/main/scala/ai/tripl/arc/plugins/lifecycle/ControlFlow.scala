package ai.tripl.arc.plugins.lifecycle

import scala.collection.JavaConverters._

import com.fasterxml.jackson.databind._

import org.apache.spark.sql.{DataFrame, SparkSession}

import ai.tripl.arc.api.API._
import ai.tripl.arc.plugins.LifecyclePlugin
import ai.tripl.arc.util.Utils
import ai.tripl.arc.config.Error._

class ControlFlow extends LifecyclePlugin with JupyterCompleter {

  val version = Utils.getFrameworkVersion

  val snippet = """%lifecycleplugin
    |{
    |  "type": "ControlFlow",
    |  "environments": [
    |    "production",
    |    "test"
    |  ]
    |}""".stripMargin

  val documentationURI = new java.net.URI(s"${baseURI}/plugins/#controlflow")

  def instantiate(index: Int, config: com.typesafe.config.Config)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Either[List[ai.tripl.arc.config.Error.StageError], LifecyclePluginInstance] = {
    import ai.tripl.arc.config.ConfigReader._
    import ai.tripl.arc.config.ConfigUtils._
    implicit val c = config

    val expectedKeys = "type" :: "environments" :: "key" :: Nil
    val key = getValue[String]("key", default = Some("controlFlowPluginOutcome"))
    val invalidKeys = checkValidKeys(c)(expectedKeys)

    (key, invalidKeys) match {
      case (Right(key), Right(invalidKeys)) =>
        Right(ControlFlowInstance(
          plugin=this,
          key=key
        ))
      case _ =>
        val allErrors: Errors = List(key, invalidKeys).collect{ case Left(errs) => errs }.flatten
        val err = StageError(index, this.getClass.getName, c.origin.lineNumber, allErrors)
        Left(err :: Nil)
    }
  }
}

case class ControlFlowInstance(
    plugin: ControlFlow,
    key: String
  ) extends LifecyclePluginInstance {

  override def runStage(stage: PipelineStage, index: Int, stages: List[PipelineStage])(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Boolean = {
    arcContext.userData.get(key) match {
      case Some(controlFlowPayload: ai.tripl.arc.execute.ControlFlowPayload) => {
        try {
           controlFlowPayload.outcome match {
            case false => {
              val log = logger.info()
                .field("event", "skip")
                .field("reason", s"skipping stage due to control flow key: '${key}' = false.")
                .map("stage", stage.stageDetail.asJava)

              // add reason message to skip message too
              (controlFlowPayload.message, controlFlowPayload.messageMap) match {
                case (Some(message), None) => log.field("message", message)
                case (None, Some(messageMap)) => log.map("message", messageMap)
                case _ =>
              }

              log.log()

              false
            }
            case true => true
          }
        } catch {
          case e: Exception => {
            logger.error()
              .field("event", "skip")
              .field("reason", s"could not convert control flow key: '${key}' to boolean.")
              .map("stage", stage.stageDetail.asJava)
              .log()

            true
          }
        }
      }
      case _ => true
    }
  }

}
