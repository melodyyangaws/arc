package ai.tripl.arc.plugins.udf

import java.io.CharArrayWriter
import java.net.URI
import java.io.InputStream
import java.io.ByteArrayInputStream
import scala.collection.JavaConverters._
import com.fasterxml.jackson.databind._

import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.fs.Path

import org.apache.commons.io.IOUtils

import org.apache.http.client.methods.{HttpGet}
import org.apache.http.impl.client.HttpClients

import org.apache.spark.sql._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions._

import ai.tripl.arc.util.log.logger.Logger
import ai.tripl.arc.api.API.ARCContext
import ai.tripl.arc.util.Utils
import ai.tripl.arc.util.ControlUtils.using

import ai.tripl.arc.util.SerializableConfiguration

import breeze.stats.distributions

class ARC extends ai.tripl.arc.plugins.UDFPlugin {

  val version = Utils.getFrameworkVersion

  // one udf plugin can register multiple user defined functions
  override def register()(implicit spark: SparkSession, logger: Logger, arcContext: ARCContext) = {

    // register custom UDFs via sqlContext.udf.register("funcName", func )
    spark.sqlContext.udf.register("get_json_double_array", ARCPlugin.getJSONDoubleArray _ )
    spark.sqlContext.udf.register("get_json_integer_array", ARCPlugin.getJSONIntArray _ )
    spark.sqlContext.udf.register("get_json_long_array", ARCPlugin.getJSONLongArray _ )
    spark.sqlContext.udf.register("get_uri", ARCPlugin.getURI _ )
    spark.sqlContext.udf.register("get_uri_array", ARCPlugin.getURIArray _ )
    spark.sqlContext.udf.register("get_uri_filename_array", ARCPlugin.getURIFilenameArray _ )
    spark.sqlContext.udf.register("random", ARCPlugin.getRandom _ )
    spark.sqlContext.udf.register("struct_keys", ARCPlugin.structKeys _ )
    spark.sqlContext.udf.register("probit", ARCPlugin.probit _ )
    spark.sqlContext.udf.register("probnorm", ARCPlugin.probnorm _ )
  }

  override def deprecations()(implicit spark: SparkSession, logger: Logger, arcContext: ARCContext) = {
    Seq(
      Deprecation("get_json_double_array", "get_json_object"),
      Deprecation("get_json_integer_array", "get_json_object"),
      Deprecation("get_json_long_array", "get_json_object")
    )
  }

}

object ARCPlugin {

  // extract the object from the json string
  def jsonPath(value: String, path: String): List[JsonNode] = {
    if (value != null) {
      if (!path.startsWith("$")) {
        throw new Exception(s"""path '${path}' must start with '$$'.""")
      }
      val objectMapper = new ObjectMapper()
      val rootNode = objectMapper.readTree(value)
      val node = rootNode.at(path.substring(1).replace(".", "/"))

      if (node.getNodeType.toString != "ARRAY") {
        throw new Exception(s"""value at '${path}' must be 'array' type.""")
      }

      node.asScala.toList
    } else {
      null
    }
  }

  // get json array cast as double
  def getJSONDoubleArray(value: String, path: String): Array[Double] = {
    Option(value) match {
      case Some(value) => {
        val node = jsonPath(value, path)
        node.map(_.asDouble).toArray
      }
      case None => null
    }
  }

  // get json array cast as integer
  def getJSONIntArray(value: String, path: String): Array[Int] = {
    if (value != null) {
      val node = jsonPath(value, path)
      node.map(_.asInt).toArray
    } else {
      null
    }
  }

  // get json array cast as long
  def getJSONLongArray(value: String, path: String): Array[Long] = {
    if (value != null) {
      val node = jsonPath(value, path)
      node.map(_.asLong).toArray
    } else {
      null
    }
  }

  def getRandom(): Double = {
    scala.util.Random.nextDouble
  }

  def structKeys(value: Row): Array[String] = {
    if (value != null) {
      value.schema.fieldNames
    } else {
      null
    }
  }

  case class URIInputStream(
    path: String,
    inputStream: InputStream
  )

  // get byte array content of uri
  def getURI(value: String)(implicit spark: SparkSession, arcContext: ARCContext): Array[Byte] = {
    if (value != null) {
      getURIFilenameArray(value).head match {
        case (byteArray, _) => byteArray
      }
    } else {
      null
    }
  }

  def getURIArray(value: String)(implicit spark: SparkSession, arcContext: ARCContext): Array[Array[Byte]] = {
    if (value != null) {
      getURIFilenameArray(value).map { case (byteArray, _) => byteArray }
    } else {
      null
    }
  }

  def getURIFilenameArray(uri: String)(implicit spark: SparkSession, arcContext: ARCContext): Array[(Array[Byte], String)] = {

    val uriInputStreams = uri match {
      case uri: String if (uri.startsWith("http") || uri.startsWith("https")) => {
        val client = HttpClients.createDefault
        try {
          val httpGet = new HttpGet(uri);
          val response = client.execute(httpGet);
          try {
            val statusCode = response.getStatusLine.getStatusCode
            val reasonPhrase = response.getStatusLine.getReasonPhrase

            if (statusCode != 200) {
              throw new Exception(s"""Expected StatusCode = 200 when GET '${uri}' but server responded with ${statusCode} (${reasonPhrase}).""")
            }
            Array(URIInputStream(uri, new ByteArrayInputStream(IOUtils.toByteArray(response.getEntity.getContent))))
          } finally {
            response.close
          }
        } finally {
          client.close
        }
      }
      case _ => {
        val hadoopConf = arcContext.serializableConfiguration.value
        val path = new Path(uri)
        val fs = path.getFileSystem(hadoopConf)

        // resolve input uri to Path as it may be a glob pattern
        val globStatus = fs.globStatus(path)
        globStatus.length match {
          case 0 => throw new Exception(s"no files found for uri '${uri}'")
          case _ => globStatus.map { fileStatus => URIInputStream(fileStatus.getPath.toString, fs.open(fileStatus.getPath)) }
        }
      }
    }

    uriInputStreams.map { uriInputStream =>
      (IOUtils.toByteArray(compressedInputStream(uriInputStream)), uriInputStream.path)
    }
  }

  // compressedInputStream wraps inputstreams with compression inputstreams based on file name
  def compressedInputStream(uriInputStream: URIInputStream)(implicit spark: SparkSession, arcContext: ARCContext): InputStream = {
    uriInputStream.path match {
      case u: String if (u.endsWith(".gzip") || u.endsWith(".gz")) => new java.util.zip.GZIPInputStream(uriInputStream.inputStream)
      case u: String if (u.endsWith(".bzip2") || u.endsWith(".bz2")) => new org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(uriInputStream.inputStream)
      case u: String if u.endsWith(".deflate") => new java.util.zip.DeflaterInputStream(uriInputStream.inputStream)
      case u: String if u.endsWith(".lz4") => new net.jpountz.lz4.LZ4FrameInputStream(uriInputStream.inputStream)
      case _ => uriInputStream.inputStream
    }
  }


  def probit(value: Double): Double = {
    distributions.Gaussian(0.0, 1.0).inverseCdf(value)
  }

  def probnorm(value: Double): Double = {
    distributions.Gaussian(0.0, 1.0).cdf(value)
  }
}
