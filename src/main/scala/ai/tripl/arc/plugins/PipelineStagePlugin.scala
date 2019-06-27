package ai.tripl.arc.plugins

import org.apache.spark.sql.{DataFrame, SparkSession}

import com.typesafe.config._

import ai.tripl.arc.api.API.{ARCContext, PipelineStage, ConfigPlugin}
import ai.tripl.arc.config.Error.StageError

trait PipelineStagePlugin extends ConfigPlugin[PipelineStage] {

}

