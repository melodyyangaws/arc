package ai.tripl.arc.transform

import org.apache.spark.sql.types.Decimal

import org.scalatest.FunSuite
import org.scalatest.BeforeAndAfter

import ai.tripl.arc.api.API._

class DecimalTypingSuite extends FunSuite with BeforeAndAfter {

  before {
  }

  after {
  }

  test("Type Decimal Column") {

    // Test trimming
    {
      val col = DecimalColumn(None, name = "name", description = Some("description"), nullable = true, nullReplacementValue = Some("42.22"), trim = true, nullableValues = "" :: Nil, precision = 4, scale = 2, metadata=None, formatters = None)
      val decimalValue = Decimal(42.22);
      // value is null -> nullReplacementValue
      {

        Typing.typeValue(null, col) match {
          case (Some(res), err) => {
            assert(res === decimalValue)
            assert(err === None)
          }
          case (_,Some(err)) => fail(err.toString)
          case (_, _) => assert(false)
        }
      }

      // value has leading spaces
      {
        Typing.typeValue("     42.22", col) match {
          case (Some(res), err) => {
            assert(res === decimalValue)
            assert(err === None)
          }
          case (_,Some(err)) => fail(err.toString)
          case (_, _) => assert(false)
        }
      }

      // value has trailing spaces
      {
        Typing.typeValue("42.22     ", col) match {
          case (Some(res), err) => {
            assert(res === decimalValue)
            assert(err === None)
          }
          case (_,Some(err)) => fail(err.toString)
          case (_, _) => assert(false)
        }
      }

      // value has leading/trailing spaces
      {
        Typing.typeValue("   42.22     ", col) match {
          case (Some(res), err) => {
            assert(res === decimalValue)
            assert(err === None)
          }
          case (_,Some(err)) => fail(err.toString)
          case (_, _) => assert(false)
        }
      }

      // value.isAllowedNullValue after trim -> nullReplacementValue
      {
        Typing.typeValue(" ", col) match {
          case (Some(res), err) => {
            assert(res === decimalValue)
            assert(err === None)
          }
          case (_,Some(err)) => fail(err.toString)
          case (_, _) => assert(false)
        }
      }

      // value contains non number/s or characters
      {
        val value = "abc"
        Typing.typeValue(value, col) match {
          case (res, Some(err)) => {
            assert(res === None)
            assert(err === TypingError("name", s"Unable to convert '${value}' to decimal(4, 2) using formatters ['#,##0.###;-#,##0.###']"))
          }
          case (_, _) => assert(false)
        }
      }
    }

    // Test not trimming
    {
      val col = DecimalColumn(None, name = "name", description = Some("description"), nullable = true, nullReplacementValue = Some("42.22"), trim = false, nullableValues = "" :: Nil, precision = 4, scale = 2, metadata=None, formatters = None)

      {
        val value = "   42.22"
        Typing.typeValue(value, col) match {
          case (res, Some(err)) => {

            assert(res === None)
            assert(err === TypingError("name", s"Unable to convert '${value}' to decimal(4, 2) using formatters ['#,##0.###;-#,##0.###']"))
          }
          case (_, _) => assert(false)
        }
      }
    }


    // Test null input WITH nullReplacementValue
    {
      val col = DecimalColumn(None, name = "name", description = Some("description"), nullable = true, nullReplacementValue = Some("42.22"), trim = false, nullableValues = "" :: Nil, precision = 4, scale = 2, metadata=None, formatters = None)
      val decimalValue = Decimal(42.22);
      // value is null -> nullReplacementValue
      {
        Typing.typeValue(null, col) match {
          case (Some(res), err) => {
            assert(res === decimalValue)
            assert(err === None)
          }
          case (_,Some(err)) => fail(err.toString)
          case (_, _) => assert(false)
        }
      }

      // value.isAllowedNullValue
      {
        Typing.typeValue("", col) match {
          case (Some(res), err) => {
            assert(res === decimalValue)
            assert(err === None)
          }
          case (_,Some(err)) => fail(err.toString)
          case (_, _) => assert(false)
        }
      }

      // value.isNotNull
      {
        Typing.typeValue("42.22", col) match {
          case (Some(res), err) => {
            assert(res === decimalValue)
            assert(err === None)
          }
          case (_,Some(err)) => fail(err.toString)
          case (_, _) => assert(false)
        }
      }
    }

    // Test null input WITHOUT nullReplacementValue
    {
      val col = DecimalColumn(None, name = "name", description = Some("description"), nullable = false, nullReplacementValue = None, trim = false, nullableValues = "" :: Nil, precision = 4, scale = 2, metadata=None, formatters = None)

      // value.isNull
      {
        Typing.typeValue(null, col) match {
          case (res, Some(err)) => {
            assert(res === None)
            assert(err === TypingError.nullReplacementValueNullErrorForCol(col))
          }
          case (_, _) => assert(false)
        }
      }

      // value.isAllowedNullValue
      {
        Typing.typeValue("", col) match {
          case (res, Some(err)) => {
            assert(res === None)
            assert(err === TypingError.nullReplacementValueNullErrorForCol(col))
          }
          case (_, _) => assert(false)
        }
      }

      // value.isNotNull
      {
        val decimalValue = Decimal(42.22);
        Typing.typeValue("42.22", col) match {
          case (Some(res), err) => {
            assert(res === decimalValue)
            assert(err === None)
          }
          case (_,Some(err)) => fail(err.toString)
          case (_, _) => assert(false)
        }
      }
    }

    // Test other miscellaneous input types

    {
      val col = DecimalColumn(None, name = "name", description = Some("description"), nullable = false, nullReplacementValue = None, trim = false, nullableValues = "" :: Nil, precision = 10, scale = 0, metadata=None, formatters = None)

      // value contains non numbers or characters
      {
        val value = "abc"
        Typing.typeValue(value, col) match {
          case (res, Some(err)) => {
            assert(res === None)
            assert(err === TypingError("name", s"Unable to convert '${value}' to decimal(10, 0) using formatters ['#,##0.###;-#,##0.###']"))
          }
          case (_, _) => assert(false)
        }
      }
    }

    // test valid precision value for Long range
    {
      val col = DecimalColumn(None, name = "name", description = Some("description"), nullable = false, nullReplacementValue = None, trim = false, nullableValues = "" :: Nil, precision = 10, scale = 0, metadata=None, formatters = None)

      // precision '10' is valid for integer value that is converted to Long
      {
        val nextVal = Int.MaxValue.toLong + 1
        val decimalValue = Decimal(nextVal);
        val value = nextVal.toString()

        Typing.typeValue(value, col) match {
          case (Some(res), err) => {
            assert(res === decimalValue)
            assert(err === None)
          }
          case (_,Some(err)) => fail(err.toString)
          case (_, _) => assert(false)
        }
      }
    }

    // test invalid precision value
    {
      val col = DecimalColumn(None, name = "name", description = Some("description"), nullable = false, nullReplacementValue = None, trim = false, nullableValues = "" :: Nil, precision = 9, scale = 0, metadata=None, formatters = None)

      // invalid precision(<10) for the value that is converted to Long
      {
        val nextVal = Int.MaxValue.toLong + 1
        val decimalValue = Decimal(nextVal);
        val value = nextVal.toString()

        Typing.typeValue(value, col) match {
          case (res, Some(err)) => {
            assert(res === None)
            assert(err === TypingError("name", s"Unable to convert '${value}' to decimal(9, 0) using formatters ['#,##0.###;-#,##0.###']"))
          }
          case (_, _) => assert(false)
        }
      }
    }

    // test invalid precision value with formatter
    {
      val formatter = "#,##0.###;#,##0.###-"
      val col = DecimalColumn(None, name = "name", description = Some("description"), nullable = false, nullReplacementValue = None, trim = false, nullableValues = "" :: Nil, precision = 9, scale = 0, metadata=None, formatters = Option(List(formatter)))

      // invalid precision(<10) for the value that is converted to Long
      {
        val value = s"${Int.MaxValue.toLong + 1}-"

        Typing.typeValue(value, col) match {
          case (res, Some(err)) => {
            assert(res === None)
            assert(err === TypingError("name", s"Unable to convert '${value}' to decimal(9, 0) using formatters ['${formatter}']"))
          }
          case (_, _) => assert(false)
        }
      }
    }

    //test negative decimal
    {
      val col = DecimalColumn(None, name = "name", description = Some("description"), nullable = false, nullReplacementValue = None, trim = false, nullableValues = "" :: Nil, precision = 4, scale = 2, metadata=None, formatters = None)

      // value contains negative number
      {
        val decimalValue = Decimal(-42.22);
        val value = "-42.22"
        Typing.typeValue(value, col) match {
          case (Some(res), err) => {
            assert(res === decimalValue)
            assert(err === None)
          }
          case (_,Some(err)) => fail(err.toString)
          case (_, _) => assert(false)
        }
      }
    }

    {
      val col = DecimalColumn(None, name = "name", description = Some("description"), nullable = false, nullReplacementValue = None, trim = false, nullableValues = "" :: Nil, precision = 4, scale = 2, metadata=None, formatters = None)

      // value contains complex characters
      {
        val value = "ኃይሌ ገብረሥላሴ"
        Typing.typeValue(value, col) match {
          case (res, Some(err)) => {
            assert(res === None)
            assert(err === TypingError("name", s"Unable to convert '${value}' to decimal(4, 2) using formatters ['#,##0.###;-#,##0.###']"))
          }
          case (_, _) => assert(false)
        }
      }
    }

    //test formatter change negative suffix
    {
      val col = DecimalColumn(None, name = "name", description = Some("description"), nullable = false, nullReplacementValue = None, trim = false, nullableValues = "" :: Nil, precision = 4, scale = 2, metadata=None, formatters = Option(List("#,##0.###;#,##0.###-")))

      // value contains negative number
      {
        val decimalValue = Decimal(-42.22);
        val value = "42.22-"
        Typing.typeValue(value, col) match {
          case (Some(res), err) => {
            assert(res === decimalValue)
            assert(err === None)
          }
          case (_,Some(err)) => fail(err.toString)
          case (_, _) => assert(false)
        }
      }
    }

    //test multiple formatter
    {
      val col = DecimalColumn(None, name = "name", description = Some("description"), nullable = false, nullReplacementValue = None, trim = false, nullableValues = "" :: Nil, precision = 4, scale = 2, metadata=None, formatters = Option(List("#,##0.###;#,##0.###-", "#,##0.###;(#,##0.###)")))

      // value contains negative number
      {
        val decimalValue = Decimal(-42.22);
        val value = "(42.22)"
        Typing.typeValue(value, col) match {
          case (Some(res), err) => {
            assert(res === decimalValue)
            assert(err === None)
          }
          case (_,Some(err)) => fail(err.toString)
          case (_, _) => assert(false)
        }
      }
    }

    //test formatter in error message
    {
      val col = DecimalColumn(None, name = "name", description = Some("description"), nullable = false, nullReplacementValue = None, trim = false, nullableValues = "" :: Nil, precision = 4, scale = 2, metadata=None, formatters = Option(List("#,##0.###;#,##0.###-")))

      // value contains negative number
      {
        val decimalValue = Decimal(-42.22);
        val value = "-42.22"
        Typing.typeValue(value, col) match {
          case (res, Some(err)) => {
            assert(res === None)
            assert(err === TypingError("name", s"Unable to convert '${value}' to decimal(4, 2) using formatters ['#,##0.###;#,##0.###-']"))
          }
          case (_, _) => assert(false)
        }
      }
    }
  }
}
