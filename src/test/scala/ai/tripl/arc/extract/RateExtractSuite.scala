package ai.tripl.arc

import java.net.URI

import org.scalatest.FunSuite
import org.scalatest.BeforeAndAfter

import collection.JavaConverters._

import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.apache.spark.sql._
import org.apache.spark.sql.functions._

import ai.tripl.arc.api._
import ai.tripl.arc.api.API._

import ai.tripl.arc.util._
import ai.tripl.arc.util.ControlUtils._

class RateExtractSuite extends FunSuite with BeforeAndAfter {

  var session: SparkSession = _

  val outputView = "outputView"

  before {
    implicit val spark = SparkSession
                  .builder()
                  .master("local[*]")
                  .config("spark.ui.port", "9999")
                  .appName("Arc Test")
                  .getOrCreate()
    spark.sparkContext.setLogLevel("INFO")

    // set for deterministic timezone
    spark.conf.set("spark.sql.session.timeZone", "UTC")

    session = spark
  }


  after {
    session.stop
  }

  test("RateExtract: Structured Streaming") {
    implicit val spark = session
    import spark.implicits._
    implicit val logger = TestUtils.getLogger()
    implicit val arcContext = TestUtils.getARCContext(isStreaming=true)

    val dataset = extract.RateExtractStage.execute(
      extract.RateExtractStage(
        plugin=new extract.RateExtract,
        id=None,
        name="dataset",
        description=None,
        outputView=outputView,
        rowsPerSecond=10,
        rampUpTime=0,
        numPartitions=1,
        params=Map.empty
      )
    ).get

    val writeStream = dataset
      .writeStream
      .queryName("extract")
      .format("memory")
      .start

    val df = spark.table("extract")

    try {
      Thread.sleep(2000)
      assert(df.count != 0)
    } finally {
      writeStream.stop
    }
  }
}
