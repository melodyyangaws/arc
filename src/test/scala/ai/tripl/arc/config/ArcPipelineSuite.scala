package ai.tripl.arc

import java.net.URI

import scala.io.Source
import scala.collection.JavaConverters._
import com.fasterxml.jackson.databind.ObjectMapper

import org.scalatest.FunSuite
import org.scalatest.BeforeAndAfter

import org.apache.spark.sql._

import ai.tripl.arc.api.API._
import ai.tripl.arc.api.{Delimited, Delimiter, QuoteCharacter}
import ai.tripl.arc.config._
import ai.tripl.arc.config.Error
import ai.tripl.arc.config.Error._
import ai.tripl.arc.util.log.LoggerFactory
import ai.tripl.arc.util.TestUtils

import com.typesafe.config._


class ConfigUtilsSuite extends FunSuite with BeforeAndAfter {

  var session: SparkSession = _

  before {
    val spark = SparkSession
                  .builder()
                  .master("local[*]")
                  .appName("Spark ETL Test")
                  .getOrCreate()
    spark.sparkContext.setLogLevel("INFO")

    // set for deterministic timezone
    spark.conf.set("spark.sql.session.timeZone", "UTC")

    session = spark
  }

  after {
    session.stop()
  }

  // test("Read simple config") {
  //   implicit val spark = session

  //   implicit val logger = TestUtils.getLogger()
  //   implicit val arcContext = TestUtils.getARCContext(isStreaming=false)

  //   val commandLineArguments = collection.mutable.HashMap[String, String]()

  //   val pipelineEither = ConfigUtils.parsePipeline(Option("classpath://conf/simple.conf"), commandLineArguments, arcContext)

  //   val stage = extract.DelimitedExtractStage(
  //     plugin=new extract.DelimitedExtract,
  //     name="file extract",
  //     description=None,
  //     schema=Right(Nil),
  //     outputView="green_tripdata0_raw",
  //     input=Right("/data/green_tripdata/0/*.csv"),
  //     settings=new Delimited(header=true, sep=Delimiter.Comma, quote=QuoteCharacter.DoubleQuote, inferSchema=false),
  //     authentication=None,
  //     params=Map.empty,
  //     persist=false,
  //     numPartitions=None,
  //     partitionBy=Nil,
  //     contiguousIndex=true,
  //     basePath=None,
  //     inputField=None
  //   )


  //   val subDelimitedExtractStage = DelimitedExtract(
  //     name = "extract data from green_tripdata/1",
  //     description=None,
  //     cols = Right(Nil),
  //     outputView = "green_tripdata1_raw",
  //     input = Right("/data/green_tripdata/1/*.csv"),
  //     settings = Delimited(Delimiter.Comma, QuoteCharacter.DoubleQuote, true, false),
  //     authentication = None,
  //     params = Map.empty,
  //     persist = false,
  //     numPartitions = None,
  //     partitionBy = Nil,
  //     contiguousIndex = true,
  //     basePath = None,
  //     inputField = None
  //   )

  //   val schema =
  //     IntegerColumn(
  //       id = "f457e562-5c7a-4215-a754-ab749509f3fb",
  //       name = "vendor_id",
  //       description = Some("A code indicating the TPEP provider that provided the record."),
  //       nullable = true,
  //       nullReplacementValue = None,
  //       trim = true,
  //       nullableValues = "" :: "null" :: Nil,
  //       metadata = None,
  //       formatters = None) ::
  //     TimestampColumn(
  //       id = "d61934ed-e32e-406b-bd18-8d6b7296a8c0",
  //       name = "lpep_pickup_datetime",
  //       description = Some("The date and time when the meter was engaged."),
  //       nullable = true,
  //       nullReplacementValue = None,
  //       trim = true,
  //       nullableValues = "" :: "null" :: Nil,
  //       timezoneId = "America/New_York",
  //       formatters = "yyyy-MM-dd HH:mm:ss" :: Nil,
  //       time = None,
  //       metadata = None,
  //       strict = false) :: Nil


  //   val subTypingTransformStage = TypingTransform(
  //     name = "apply green_tripdata/1 data types",
  //     description=None,
  //     cols = Right(schema),
  //     inputView = "green_tripdata1_raw",
  //     outputView = "green_tripdata1",
  //     params = Map.empty,
  //     persist=true,
  //     failMode=FailModeTypePermissive,
  //     numPartitions=None,
  //     partitionBy=Nil
  //   )

  //   val subSQLValidateStage = SQLValidate(
  //     name = "ensure no errors exist after data typing",
  //     description=None,
  //     inputURI = URI.create("classpath://conf/sql/sqlvalidate_errors.sql"),
  //     sql =
  //       """|SELECT
  //          |  SUM(error) = 0
  //          |  ,TO_JSON(NAMED_STRUCT('count', COUNT(error), 'errors', SUM(error)))
  //          |FROM (
  //          |  SELECT
  //          |    CASE
  //          |      WHEN SIZE(_errors) > 0 THEN ${test_integer}
  //          |      ELSE 0
  //          |    END AS error
  //          |  FROM ${table_name}
  //          |) input_table""".stripMargin,
  //     sqlParams = Map("table_name" -> "green_tripdata1", "test_integer" -> "1"),
  //     params = Map.empty
  //   )

  //   val expected = ETLPipeline(stage :: subDelimitedExtractStage :: subTypingTransformStage :: subSQLValidateStage :: Nil)

  //   pipelineEither match {
  //     case Left(errors) => assert(false)
  //     case Right((pipeline, _)) => {
  //       assert(pipeline === expected)
  //     }
  //   }
  // }

  // This test loops through the /src/test/resources/docs_resources directory and tries to parse each file as a config
  // the same config files are used (embedded) on the documentation site so this ensures the examples will work.
  test("Read documentation config files") {
    implicit val spark = session
    implicit val logger = TestUtils.getLogger()
    var commandLineArguments = Map[String, String]("JOB_RUN_DATE" -> "0", "ETL_CONF_BASE_URL" -> "")
    implicit val arcContext = TestUtils.getARCContext(isStreaming=false, commandLineArguments=commandLineArguments)

    val resourcesDir = getClass.getResource("/docs_resources/").getPath

    // this is used to test that vertexExists works with predfefined tables like from a hive metastore
    val emptyDF = spark.emptyDataFrame
    emptyDF.createOrReplaceTempView("customer")

    for (filename <- TestUtils.getListOfFiles(resourcesDir)) {
      val fileContents = Source.fromFile(filename).mkString

      // inject a stage to register the 'customer' view to stop downstream stages breaking
      val conf = s"""{"stages": [${fileContents.trim}]}"""

      // replace sql directory with config so that the examples read correctly but have resource to validate
      val sqlConf = conf.replaceAll("hdfs://datalake/sql/", getClass.getResource("/conf/sql/").toString)

      // replace ml directory with config so that the examples read correctly but have resource to validate
      val mlConf = sqlConf.replaceAll("hdfs://datalake/ml/", getClass.getResource("/conf/ml/").toString)

      // replace meta directory with config so that the examples read correctly but have resource to validate
      val metaConf = mlConf.replaceAll("hdfs://datalake/metadata/", getClass.getResource("/conf/metadata/").toString)

      // replace job directory with config so that the examples read correctly but have resource to validate
      val jobConf = metaConf.replaceAll("hdfs://datalake/job/", getClass.getResource("/conf/job/").toString)

      try {
        val pipelineEither = ArcPipeline.parseConfig(Left(jobConf), arcContext)

        pipelineEither match {
          case Left(errors) => {
            assert(false, s"Error in config ${filename}: ${Error.pipelineErrorMsg(errors)}")
          }
          case Right(pipeline) => {
            assert(true)
          }
        }
      } catch {
        case e: Exception => {
          println(s"error in: ${filename}\nerror: ${e}\ncontents: ${jobConf}")
          assert(false)
        }
      }
    }
  }

  test("Test missing keys exception") {
    implicit val spark = session
    implicit val logger = TestUtils.getLogger()
    implicit val arcContext = TestUtils.getARCContext(isStreaming=false)

    val conf = """{
      "stages": [
        {
          "type": "DelimitedExtract",
          "name": "file extract",
          "environments": [
            "production",
            "test"
          ]
        }
      ]
    }"""

    val pipelineEither = ArcPipeline.parseConfig(Left(conf), arcContext)

    pipelineEither match {
      case Left(stageError) => {
        assert(stageError ==
        StageError(0, "file extract",3,List(
            ConfigError("inputURI", None, "Missing required attribute 'inputURI'.")
            ,ConfigError("outputView", None, "Missing required attribute 'outputView'.")
          )
        ) :: Nil)
      }
      case Right(_) => assert(false)
    }
  }

  test("Test extraneous attributes") {
    implicit val spark = session
    implicit val logger = TestUtils.getLogger()
    implicit val arcContext = TestUtils.getARCContext(isStreaming=false)

    val conf = """{
      "stages": [
        {
          "type": "DelimitedExtract",
          "name": "file extract 0",
          "environments": [
            "production",
            "test"
          ],
          "inputURI": "/tmp/test.csv",
          "outputView": "output",
        },
        {
          "type": "DelimitedExtract",
          "name": "file extract 1",
          "environments": [
            "production",
            "test"
          ],
          "inputURI": "/tmp/test.csv",
          "outputVew": "output",
          "nothinglikeanything": false
        }
      ]
    }"""

    val pipelineEither = ArcPipeline.parseConfig(Left(conf), arcContext)

    pipelineEither match {
      case Left(stageError) => {
        assert(stageError ==
        StageError(1, "file extract 1",13,List(
            ConfigError("outputView", None, "Missing required attribute 'outputView'.")
            ,ConfigError("nothinglikeanything", Some(22), "Invalid attribute 'nothinglikeanything'.")
            ,ConfigError("outputVew", Some(21), "Invalid attribute 'outputVew'. Perhaps you meant one of: ['outputView'].")
          )
        ) :: Nil)
      }
      case Right(_) => assert(false)
    }
  }

  test("Test invalid validValues") {
    implicit val spark = session
    implicit val logger = TestUtils.getLogger()
    implicit val arcContext = TestUtils.getARCContext(isStreaming=false)

    val conf = """{
      "stages": [
        {
          "type": "DelimitedExtract",
          "name": "file extract",
          "environments": [
            "production",
            "test"
          ],
          "inputURI": "/tmp/test.csv",
          "outputView": "output",
          "delimiter": "abc"
        }
      ]
    }"""

    val pipelineEither = ArcPipeline.parseConfig(Left(conf), arcContext)

    pipelineEither match {
      case Left(stageError) => {
        assert(stageError == StageError(0, "file extract",3,List(ConfigError("delimiter", Some(12), "Invalid value. Valid values are ['Comma','Pipe','DefaultHive','Custom']."))) :: Nil)
      }
      case Right(_) => assert(false)
    }
  }

  test("Test read custom delimiter") {
    implicit val spark = session
    implicit val logger = TestUtils.getLogger()
    implicit val arcContext = TestUtils.getARCContext(isStreaming=false)

    val conf = """{
      "stages": [
        {
          "type": "DelimitedExtract",
          "name": "file extract",
          "environments": [
            "production",
            "test"
          ],
          "inputURI": "/tmp/test.csv",
          "outputView": "output",
          "delimiter": "Custom"
        }
      ]
    }"""

    val pipelineEither = ArcPipeline.parseConfig(Left(conf), arcContext)

    pipelineEither match {
      case Left(stageError) => {
        assert(stageError == StageError(0, "file extract",3,List(ConfigError("customDelimiter", None, "Missing required attribute 'customDelimiter'."))) :: Nil)
      }
      case Right(_) => assert(false)
    }
  }

  test("Test read custom delimiter success") {
    implicit val spark = session
    implicit val logger = TestUtils.getLogger()
    implicit val arcContext = TestUtils.getARCContext(isStreaming=false)

    val conf = """{
      "stages": [
        {
          "type": "DelimitedExtract",
          "name": "file extract",
          "environments": [
            "production",
            "test"
          ],
          "inputURI": "/tmp/test.csv",
          "outputView": "output",
          "delimiter": "Custom",
          "customDelimiter": "%"
        }
      ]
    }"""

    val pipelineEither = ArcPipeline.parseConfig(Left(conf), arcContext)

    pipelineEither match {
      case Left(errors) => assert(false)
      case Right((pipeline, _)) => {
        pipeline.stages(0) match {
          case s: extract.DelimitedExtractStage => assert(s.settings.customDelimiter == "%")
          case _ => assert(false)
        }
      }
    }
  }

  test("Test config substitutions") {
    implicit val spark = session
    implicit val logger = TestUtils.getLogger()
    implicit val arcContext = TestUtils.getARCContext(isStreaming=false)

    val conf = """{
      "common": {
        "environments": [
            "production",
            "test"
        ],
        "name": "foo"
      },
      "stages": [
        {
          "environments": ${common.environments}
          "type": "RateExtract",
          "name": ${common.name},
          "outputView": "stream",
          "rowsPerSecond": 1,
          "rampUpTime": 1,
          "numPartitions": 1
        }
      ]
    }"""


    val pipelineEither = ArcPipeline.parseConfig(Left(conf), arcContext)

    pipelineEither match {
      case Left(errors) => assert(false)
      case Right((pipeline, _)) => {
        assert(pipeline.stages(0).name == "foo")
      }
    }
  }

  test("Test not List[Object]") {
    implicit val spark = session
    implicit val logger = TestUtils.getLogger()
    implicit val arcContext = TestUtils.getARCContext(isStreaming=false)

    val conf = """{
      "stages":
      {
        "type": "RateExtract",
        "name": "RateExtract",
        "environments": [
          "production",
          "test"
        ],
        "outputView": "stream",
        "rowsPerSecond": 1,
        "rampUpTime": 1,
        "numPartitions": 1
      }
    }"""


    val pipelineEither = ArcPipeline.parseConfig(Left(conf), arcContext)

    pipelineEither match {
      case Left(errors) => {
        assert(errors.toString contains "Expected stages to be a List of Objects")
      }
      case Right((pipeline, _)) => {
        assert(false)
      }
    }
  }

  // this test reads a pipeline of sqltransforms which depend on the previous stage being run (including subpiplines)
  // this is to ensure that the stages are executed in the correct order
  test("Test read correct order") {
    implicit val spark = session
    implicit val logger = TestUtils.getLogger()
    implicit val arcContext = TestUtils.getARCContext(isStreaming=false)

    val df = TestUtils.getKnownDataset
    df.createOrReplaceTempView("start")

    val pipelineEither = ArcPipeline.parseConfig(Right(new URI("classpath://conf/pipeline.conf")), arcContext)

    pipelineEither match {
      case Left(errors) => {
        println(errors)
        assert(false)
      }
      case Right((pipeline, _)) => {
        ARC.run(pipeline)
        assert(spark.sql("SELECT * FROM stage4").count == 2)
      }
    }

  }

}
