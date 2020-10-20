package ai.tripl.arc.transform

import java.net.URI
import scala.collection.JavaConverters._

import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.client.LaxRedirectStrategy

import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.node._

import org.apache.spark.sql._
import org.apache.spark.sql.types._

import scala.io.Source

import ai.tripl.arc.api.API._
import ai.tripl.arc.util._

import com.typesafe.config._

import ai.tripl.arc.api.API._
import ai.tripl.arc.config._
import ai.tripl.arc.config.Error._
import ai.tripl.arc.plugins.PipelineStagePlugin
import ai.tripl.arc.util.DetailException
import ai.tripl.arc.util.EitherUtils._
import ai.tripl.arc.util.Utils

class TensorFlowServingTransform extends PipelineStagePlugin with JupyterCompleter {

  val version = Utils.getFrameworkVersion

  val snippet = """{
    |  "type": "TensorFlowServingTransform",
    |  "name": "TensorFlowServingTransform",
    |  "environments": [
    |    "production",
    |    "test"
    |  ],
    |  "inputView": "inputView",
    |  "uri": "http://",
    |  "batchSize": 100,
    |  "outputView": "outputView"
    |}""".stripMargin

  val documentationURI = new java.net.URI(s"${baseURI}/transform/#tensorflowservingtransform")

  def instantiate(index: Int, config: com.typesafe.config.Config)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Either[List[ai.tripl.arc.config.Error.StageError], PipelineStage] = {
    import ai.tripl.arc.config.ConfigReader._
    import ai.tripl.arc.config.ConfigUtils._
    implicit val c = config

    val expectedKeys = "type" :: "id" :: "name" :: "description" :: "environments" :: "inputView" :: "outputView" :: "uri" :: "batchSize" :: "inputField" :: "params"  :: "persist" :: "responseType" :: "signatureName" :: "numPartitions" :: "partitionBy" :: Nil
    val id = getOptionalValue[String]("id")
    val name = getValue[String]("name")
    val description = getOptionalValue[String]("description")
    val inputView = getValue[String]("inputView")
    val outputView = getValue[String]("outputView")
    val uri = getValue[String]("uri") |> parseURI("uri") _
    val inputField = getValue[String]("inputField", default = Some("value"))
    val signatureName = getOptionalValue[String]("signatureName")
    val batchSize = getValue[Int]("batchSize", default = Some(100))
    val persist = getValue[java.lang.Boolean]("persist", default = Some(false))
    val responseType = getValue[String]("responseType", default = Some("object"), validValues = "integer" :: "double" :: "object" :: Nil) |> parseResponseType("responseType") _
    val numPartitions = getOptionalValue[Int]("numPartitions")
    val partitionBy = getValue[StringList]("partitionBy", default = Some(Nil))
    val params = readMap("params", c)
    val invalidKeys = checkValidKeys(c)(expectedKeys)

    (id, name, description, inputView, outputView, uri, signatureName, responseType, batchSize, persist, inputField, numPartitions, partitionBy, invalidKeys) match {
      case (Right(id), Right(name), Right(description), Right(inputView), Right(outputView), Right(uri), Right(signatureName), Right(responseType), Right(batchSize), Right(persist), Right(inputField), Right(numPartitions), Right(partitionBy), Right(invalidKeys)) =>

        val stage = TensorFlowServingTransformStage(
          plugin=this,
          id=id,
          name=name,
          description=description,
          inputView=inputView,
          outputView=outputView,
          uri=uri,
          signatureName=signatureName,
          responseType=responseType,
          batchSize=batchSize,
          inputField=inputField,
          params=params,
          persist=persist,
          numPartitions=numPartitions,
          partitionBy=partitionBy
        )

        numPartitions.foreach { numPartitions => stage.stageDetail.put("numPartitions", Integer.valueOf(numPartitions)) }
        signatureName.foreach { stage.stageDetail.put("signatureName", _)}
        stage.stageDetail.put("batchSize", java.lang.Integer.valueOf(batchSize))
        stage.stageDetail.put("inputField", inputField)
        stage.stageDetail.put("inputView", inputView)
        stage.stageDetail.put("outputView", outputView)
        stage.stageDetail.put("partitionBy", partitionBy.asJava)
        stage.stageDetail.put("persist", java.lang.Boolean.valueOf(persist))
        stage.stageDetail.put("responseType", responseType.sparkString)
        stage.stageDetail.put("uri", uri.toString)

        Right(stage)
      case _ =>
        val allErrors: Errors = List(id, name, description, inputView, outputView, uri, signatureName, responseType, batchSize, persist, inputField, numPartitions, partitionBy, invalidKeys).collect{ case Left(errs) => errs }.flatten
        val stageName = stringOrDefault(name, "unnamed stage")
        val err = StageError(index, stageName, c.origin.lineNumber, allErrors)
        Left(err :: Nil)
    }
  }

  def parseResponseType(path: String)(delim: String)(implicit c: Config): Either[Errors, ResponseType] = {
    delim.toLowerCase.trim match {
      case "integer" => Right(ResponseType.IntegerResponse)
      case "double" => Right(ResponseType.DoubleResponse)
      case "object" => Right(ResponseType.StringResponse)
      case _ => Left(ConfigError(path, None, s"Invalid state. Please raise issue.") :: Nil)
    }
  }

}

case class TensorFlowServingTransformStage(
    plugin: TensorFlowServingTransform,
    id: Option[String],
    name: String,
    description: Option[String],
    inputView: String,
    outputView: String,
    uri: URI,
    signatureName: Option[String],
    responseType: ResponseType,
    batchSize: Int,
    inputField: String,
    params: Map[String, String],
    persist: Boolean,
    numPartitions: Option[Int],
    partitionBy: List[String]
  ) extends TransformPipelineStage {

  override def execute()(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Option[DataFrame] = {
    TensorFlowServingTransformStage.execute(this)
  }

}

object TensorFlowServingTransformStage {

  type TensorFlowResponseRow = Row

  def execute(stage: TensorFlowServingTransformStage)(implicit spark: SparkSession, logger: ai.tripl.arc.util.log.logger.Logger, arcContext: ARCContext): Option[DataFrame] = {

    val df = spark.table(stage.inputView)
    val stageUri = stage.uri
    val stageInputField = stage.inputField
    val stageBatchSize = stage.batchSize
    val stageSignatureName = stage.signatureName
    val stageResponseType = stage.responseType

    if (!df.columns.contains(stage.inputField)) {
      throw new Exception(s"""inputField '${stage.inputField}' is not present in inputView '${stage.inputView}' which has: [${df.columns.mkString(", ")}] columns.""") with DetailException {
        override val detail = stage.stageDetail
      }
    }

    val tensorFlowResponseSchema = stage.responseType match {
      case ResponseType.IntegerResponse => StructType(df.schema.fields.toList ::: List(new StructField("result", IntegerType, true)))
      case ResponseType.DoubleResponse => StructType(df.schema.fields.toList ::: List(new StructField("result", DoubleType, true)))
      case _ => StructType(df.schema.fields.toList ::: List(new StructField("result", StringType, true)))
    }

    implicit val typedEncoder: Encoder[TensorFlowResponseRow] = org.apache.spark.sql.catalyst.encoders.RowEncoder(tensorFlowResponseSchema)

    val transformedDF = try {
      df.mapPartitions[TensorFlowResponseRow] { partition: Iterator[Row] =>

        val poolingHttpClientConnectionManager = new PoolingHttpClientConnectionManager()
        poolingHttpClientConnectionManager.setMaxTotal(50)
        val httpClient = HttpClients.custom()
                .setConnectionManager(poolingHttpClientConnectionManager)
                .setRedirectStrategy(new LaxRedirectStrategy())
                .build()
        val uri = stageUri

        val validStatusCodes = 200 :: 201 :: 202 :: Nil

        val objectMapper = new ObjectMapper()

        // get type and index so it doesnt have to be resolved for each row
        val bufferedPartition = partition.buffered
        val fieldIndex = bufferedPartition.hasNext match {
          case true => bufferedPartition.head.fieldIndex(stageInputField)
          case false => 0
        }
        val dataType = bufferedPartition.hasNext match {
          case true => bufferedPartition.head.schema(fieldIndex).dataType
          case false => NullType
        }

        // group so we can send multiple rows per request
        val groupedPartition = bufferedPartition.grouped(stageBatchSize)

        groupedPartition.flatMap[TensorFlowResponseRow] { groupedRow =>

          val jsonNodeFactory = new JsonNodeFactory(true)
          val node = jsonNodeFactory.objectNode

          stageSignatureName.foreach { node.put("signature_name", _) }

          // add payload to array
          val instancesArray = node.putArray("instances")
          groupedRow.foreach(row => {
            dataType match {
              // for StringType first try to deserialise object so it can be properly serialised in the batch
              case _: StringType => instancesArray.add(objectMapper.readTree(row.getString(fieldIndex)))
              case _: IntegerType => instancesArray.add(row.getInt(fieldIndex))
              case _: LongType => instancesArray.add(row.getLong(fieldIndex))
              case _: FloatType => instancesArray.add(row.getFloat(fieldIndex))
              case _: DoubleType => instancesArray.add(row.getDouble(fieldIndex))
              case _: DecimalType => instancesArray.add(row.getDecimal(fieldIndex))
            }
          })

          val post = new HttpPost(uri)

          val response = try {
            // add the stringified json object to the request body
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(node)))

            val response = httpClient.execute(post)

            // read and close response
            val responseEntity = response.getEntity.getContent
            val body = Source.fromInputStream(responseEntity).mkString
            response.close

            // verify status code is correct
            if (!validStatusCodes.contains(response.getStatusLine.getStatusCode)) {
              throw new Exception(body)
            }

            // decode the response
            val rootNode = objectMapper.readTree(body)
            rootNode.get("predictions").asScala.toList

          } finally {
            post.releaseConnection
          }

          // try to unpack result
          groupedRow.zipWithIndex.map { case (row, index) => {
              val result = stageResponseType match {
                case ResponseType.IntegerResponse => Seq(response(index).asInt)
                case ResponseType.DoubleResponse => Seq(response(index).asDouble)
                case _ => Seq(response(index).asText)
              }
              Row.fromSeq(row.toSeq ++ result).asInstanceOf[TensorFlowResponseRow]
            }
          }
        }
      }
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

    if (arcContext.immutableViews) repartitionedDF.createTempView(stage.outputView) else repartitionedDF.createOrReplaceTempView(stage.outputView)

    if (!repartitionedDF.isStreaming) {
      stage.stageDetail.put("outputColumns", java.lang.Integer.valueOf(repartitionedDF.schema.length))
      stage.stageDetail.put("numPartitions", java.lang.Integer.valueOf(repartitionedDF.rdd.partitions.length))

      if (stage.persist) {
        spark.catalog.cacheTable(stage.outputView, arcContext.storageLevel)
        stage.stageDetail.put("records", java.lang.Long.valueOf(repartitionedDF.count))
      }
    }

    Option(repartitionedDF)
  }
}
