package com.guizmaii.csvzen.core

import zio.Scope
import zio.test.*

import java.io.StringWriter

object CsvWriterSpec extends ZIOSpecDefault {

  private def writeToString(config: CsvConfig = CsvConfig.default)(body: CsvWriter => Unit): String = {
    val sw = new StringWriter
    val w  = CsvWriter.unsafeFromWriter(sw, config)
    try body(w)
    finally w.close()
    sw.toString
  }

  // --- Baseline writeRow behaviours ----------------------------------------

  private val baselineWriteRowSpec =
    suite("::writeRow (lambda escape-hatch)")(
      test("writes plain fields separated by the delimiter and terminated by the line terminator") {
        val out = writeToString()(_.writeRow { e => e.emitString("a"); e.emitString("b"); e.emitString("c") })
        assertTrue(out == "a,b,c\r\n")
      },
      test("emits a lone line terminator for a row with no fields") {
        val out = writeToString()(_.writeRow(_ => ()))
        assertTrue(out == "\r\n")
      },
      test("writes a single-field row without a trailing delimiter") {
        val out = writeToString()(_.writeRow(_.emitString("only")))
        assertTrue(out == "only\r\n")
      },
      test("writes empty-string fields as empty cells") {
        val out = writeToString()(_.writeRow { e => e.emitString(""); e.emitString(""); e.emitString("") })
        assertTrue(out == ",,\r\n")
      },
      test("quotes a field that contains the delimiter") {
        val out = writeToString()(_.writeRow { e => e.emitString("a,b"); e.emitString("c") })
        assertTrue(out == "\"a,b\",c\r\n")
      },
      test("quotes a field that contains a double quote and doubles the internal quote") {
        val out = writeToString()(_.writeRow(_.emitString("he said \"hi\"")))
        assertTrue(out == "\"he said \"\"hi\"\"\"\r\n")
      },
      test("quotes a field that contains an LF") {
        val out = writeToString()(_.writeRow(_.emitString("line1\nline2")))
        assertTrue(out == "\"line1\nline2\"\r\n")
      },
      test("quotes a field that contains a CR") {
        val out = writeToString()(_.writeRow(_.emitString("line1\rline2")))
        assertTrue(out == "\"line1\rline2\"\r\n")
      },
      test("quotes a field that contains a CRLF") {
        val out = writeToString()(_.writeRow(_.emitString("line1\r\nline2")))
        assertTrue(out == "\"line1\r\nline2\"\r\n")
      },
      test("honours a non-default delimiter and quote character") {
        val tsvWithHash = CsvConfig(delimiter = '\t', quoteChar = '#')
        val out         = writeToString(tsvWithHash)(_.writeRow { e => e.emitString("a\tb"); e.emitString("c#d") })
        assertTrue(out == "#a\tb#\t#c##d#\r\n")
      },
      test("honours a non-default line terminator") {
        val lf  = CsvConfig(lineTerminator = "\n")
        val out = writeToString(lf)(_.writeRow { e => e.emitString("a"); e.emitString("b") })
        assertTrue(out == "a,b\n")
      },
    )

  // --- writeHeader(IndexedSeq) --------------------------------------------

  private val writeHeaderManualSpec =
    suite("::writeHeader(IndexedSeq)")(
      test("writes the column names joined by the delimiter and terminated by the line terminator") {
        val out = writeToString()(_.writeHeader(IndexedSeq("name", "age", "city")))
        assertTrue(out == "name,age,city\r\n")
      },
      test("escapes header names that contain specials") {
        val out = writeToString()(_.writeHeader(IndexedSeq("first,name", "age")))
        assertTrue(out == "\"first,name\",age\r\n")
      },
      test("writes nothing data-wise for an empty name list, just terminator") {
        val out = writeToString()(_.writeHeader(IndexedSeq.empty))
        assertTrue(out == "\r\n")
      },
    )

  // --- writeAll (codec-driven) ---------------------------------------------

  private final case class Pair(a: Int, b: String) derives CsvRowEncoder

  private val writeAllSpec =
    suite("::writeAll")(
      test("writes one CSV row per input element, in iteration order (Vector)") {
        val rows = Vector(Pair(1, "a"), Pair(2, "b"), Pair(3, "c"))
        val out  = writeToString()(_.writeAll(rows))
        assertTrue(out == "1,a\r\n2,b\r\n3,c\r\n")
      },
      test("writes nothing for an empty input") {
        val out = writeToString()(_.writeAll(Vector.empty[Pair]))
        assertTrue(out == "")
      },
      test("iterates a List in order") {
        val out = writeToString()(_.writeAll(List(Pair(10, "x"), Pair(20, "y"), Pair(30, "z"))))
        assertTrue(out == "10,x\r\n20,y\r\n30,z\r\n")
      },
      test("iterates a LazyList in order") {
        val out = writeToString()(_.writeAll(LazyList(Pair(7, "q"), Pair(8, "r"), Pair(9, "s"))))
        assertTrue(out == "7,q\r\n8,r\r\n9,s\r\n")
      },
    )

  // --- writeRow[A] (codec path) -------------------------------------------

  private final case class Mixed(
    s: String,
    i: Int,
    l: Long,
    sh: Short,
    b: Byte,
    bool: Boolean,
    c: Char,
    f: Float,
    d: Double,
    opt: Option[String],
  ) derives CsvRowEncoder

  private val writeRowCodecSpec =
    suite("::writeRow[A] (codec path)")(
      test("encodes every shipped primitive in declaration order") {
        val m   = Mixed("hello", 42, 9999999999L, 7.toShort, (-1).toByte, true, 'x', 1.5f, 2.5, Some("opt"))
        val out = writeToString()(_.writeRow(m))
        assertTrue(out == "hello,42,9999999999,7,-1,true,x,1.5,2.5,opt\r\n")
      },
      test("encodes Option[None] as empty cell") {
        val m   = Mixed("s", 1, 2L, 3.toShort, 4.toByte, false, 'c', 0.0f, 0.0, None)
        val out = writeToString()(_.writeRow(m))
        assertTrue(out == "s,1,2,3,4,false,c,0.0,0.0,\r\n")
      },
      test("escapes string values containing specials") {
        final case class R(s: String) derives CsvRowEncoder
        val out = writeToString()(_.writeRow(R("a,b")))
        assertTrue(out == "\"a,b\"\r\n")
      },
    )

  // --- writeHeader[A] (derived) -------------------------------------------

  private val writeHeaderDerivedSpec =
    suite("::writeHeader[A] (derived)")(
      test("emits labels in declaration order for Pair") {
        val out = writeToString()(_.writeHeader[Pair]())
        assertTrue(out == "a,b\r\n")
      },
      test("emits labels for Mixed (10-field case class)") {
        val out = writeToString()(_.writeHeader[Mixed]())
        assertTrue(out == "s,i,l,sh,b,bool,c,f,d,opt\r\n")
      },
    )

  // --- close semantics -----------------------------------------------------

  private val closeSpec =
    suite("::close")(
      test("closes the underlying Writer") {
        final class TrackingWriter extends java.io.Writer {
          var closed: Int                                        = 0
          val buf                                                = new StringBuilder
          def write(cbuf: Array[Char], off: Int, len: Int): Unit = { val _ = buf.appendAll(cbuf, off, len) }
          def flush(): Unit                                      = ()
          override def close(): Unit                             = closed += 1
        }
        val tw = new TrackingWriter
        val w = CsvWriter.unsafeFromWriter(tw, CsvConfig.default)
        w.writeRow(_.emitString("x"))
        w.close()
        assertTrue(tw.closed == 1)
      }
    )

  // --- non-default configs through the codec path --------------------------

  private val configPropagationSpec =
    suite("codec path honours config")(
      test("TSV config routes through FieldEmitter's delimiter") {
        val tsv = CsvConfig(delimiter = '\t')
        val out = writeToString(tsv)(_.writeRow(Pair(1, "a")))
        assertTrue(out == "1\ta\r\n")
      },
      test("semicolon delimiter and LF terminator") {
        val cfg = CsvConfig(delimiter = ';', lineTerminator = "\n")
        val out = writeToString(cfg)(_.writeRow(Pair(1, "a")))
        assertTrue(out == "1;a\n")
      },
    )

  // --- flush() --------------------------------------------------------------

  private val flushSpec =
    suite("::flush")(
      test("flushes the underlying Writer") {
        final class TrackingWriter extends java.io.Writer {
          var flushes: Int                                       = 0
          def write(cbuf: Array[Char], off: Int, len: Int): Unit = ()
          def flush(): Unit                                      = flushes += 1
          override def close(): Unit                             = ()
        }
        val tw = new TrackingWriter
        val w      = CsvWriter.unsafeFromWriter(tw, CsvConfig.default)
        w.writeRow(_.emitString("x"))
        val before = tw.flushes
        w.flush()
        val after  = tw.flushes
        w.close()
        assertTrue(before == 0, after == 1)
      }
    )

  // --- CsvConfig validation -------------------------------------------------

  private val configValidationSpec =
    suite("CsvConfig validation")(
      test("rejects delimiter == quoteChar") {
        val ex = scala.util.Try(CsvConfig(delimiter = '"', quoteChar = '"'))
        assertTrue(ex.isFailure, ex.failed.toOption.exists(_.isInstanceOf[IllegalArgumentException]))
      },
      test("rejects delimiter = CR or LF") {
        val ex1 = scala.util.Try(CsvConfig(delimiter = '\r'))
        val ex2 = scala.util.Try(CsvConfig(delimiter = '\n'))
        assertTrue(ex1.isFailure, ex2.isFailure)
      },
      test("rejects quoteChar = CR or LF") {
        val ex1 = scala.util.Try(CsvConfig(quoteChar = '\r'))
        val ex2 = scala.util.Try(CsvConfig(quoteChar = '\n'))
        assertTrue(ex1.isFailure, ex2.isFailure)
      },
      test("rejects lineTerminator outside {\"\\n\", \"\\r\", \"\\r\\n\"}") {
        val cases = Seq("", ";", "X", "\r\n\r\n", " ")
        assertTrue(cases.forall(t => scala.util.Try(CsvConfig(lineTerminator = t)).isFailure))
      },
      test("accepts each of the three valid lineTerminators") {
        val cases = Seq("\n", "\r", "\r\n")
        assertTrue(cases.forall(t => scala.util.Try(CsvConfig(lineTerminator = t)).isSuccess))
      },
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("CsvWriter")(
      baselineWriteRowSpec,
      writeHeaderManualSpec,
      writeAllSpec,
      writeRowCodecSpec,
      writeHeaderDerivedSpec,
      closeSpec,
      flushSpec,
      configPropagationSpec,
      configValidationSpec,
    )
}
