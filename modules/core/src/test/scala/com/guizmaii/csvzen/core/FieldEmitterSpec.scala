package com.guizmaii.csvzen.core

import zio.Scope
import zio.test.*

import java.io.StringWriter

object FieldEmitterSpec extends ZIOSpecDefault {

  private def emitWith(config: CsvConfig)(body: FieldEmitter => Unit): String = {
    val sw = new StringWriter
    val w  = CsvWriter.unsafeFromWriter(sw, config)
    try w.writeRow(body)
    finally w.close()
    sw.toString
  }

  private def firstField(config: CsvConfig)(body: FieldEmitter => Unit): String = {
    val full = emitWith(config)(body)
    // strip line terminator
    full.stripSuffix(config.lineTerminator)
  }

  private val cfg = CsvConfig.default

  private val emitIntSpec =
    suite("::emitInt")(
      test("writes 0") {
        assertTrue(firstField(cfg)(_.emitInt(0)) == "0")
      },
      test("writes positive values") {
        val values = Seq(1, 9, 10, 99, 100, 12345, Int.MaxValue - 1, Int.MaxValue)
        assertTrue(values.forall(v => firstField(cfg)(_.emitInt(v)) == v.toString))
      },
      test("writes negative values") {
        val values = Seq(-1, -9, -10, -99, -100, -12345, Int.MinValue + 1, Int.MinValue)
        assertTrue(values.forall(v => firstField(cfg)(_.emitInt(v)) == v.toString))
      },
      test("matches Integer.toString on any Int (PBT)") {
        check(Gen.int) { v =>
          assertTrue(firstField(cfg)(_.emitInt(v)) == Integer.toString(v))
        }
      },
    )

  private val emitLongSpec =
    suite("::emitLong")(
      test("writes 0L") {
        assertTrue(firstField(cfg)(_.emitLong(0L)) == "0")
      },
      test("writes boundary values") {
        val values = Seq(
          1L,
          -1L,
          10L,
          -10L,
          99L,
          -99L,
          Int.MaxValue.toLong + 1L,
          Int.MinValue.toLong - 1L,
          Long.MaxValue,
          Long.MinValue,
        )
        assertTrue(values.forall(v => firstField(cfg)(_.emitLong(v)) == v.toString))
      },
      test("matches java.lang.Long.toString on any Long (PBT)") {
        check(Gen.long) { v =>
          assertTrue(firstField(cfg)(_.emitLong(v)) == java.lang.Long.toString(v))
        }
      },
    )

  private val emitShortSpec =
    suite("::emitShort")(
      test("exhaustive over full Short range") {
        val ok = (Short.MinValue.toInt to Short.MaxValue.toInt).forall { iv =>
          val v = iv.toShort
          firstField(cfg)(_.emitShort(v)) == v.toString
        }
        assertTrue(ok)
      }
    )

  private val emitByteSpec =
    suite("::emitByte")(
      test("exhaustive over full Byte range") {
        val ok = (Byte.MinValue.toInt to Byte.MaxValue.toInt).forall { iv =>
          val v = iv.toByte
          firstField(cfg)(_.emitByte(v)) == v.toString
        }
        assertTrue(ok)
      }
    )

  private val emitBooleanSpec =
    suite("::emitBoolean")(
      test("writes true") {
        assertTrue(firstField(cfg)(_.emitBoolean(true)) == "true")
      },
      test("writes false") {
        assertTrue(firstField(cfg)(_.emitBoolean(false)) == "false")
      },
    )

  private val emitFloatSpec =
    suite("::emitFloat")(
      test("matches Float.toString on key values") {
        val values =
          Seq(
            0.0f,
            -0.0f,
            1.0f,
            -1.0f,
            Float.MinPositiveValue,
            Float.MinValue,
            Float.MaxValue,
            math.Pi.toFloat,
            math.E.toFloat,
            Float.PositiveInfinity,
            Float.NegativeInfinity,
            Float.NaN
          )
        assertTrue(values.forall(v => firstField(cfg)(_.emitFloat(v)) == java.lang.Float.toString(v)))
      }
    )

  private val emitDoubleSpec =
    suite("::emitDouble")(
      test("matches Double.toString on key values") {
        val values =
          Seq(
            0.0,
            -0.0,
            1.0,
            -1.0,
            Double.MinPositiveValue,
            Double.MinValue,
            Double.MaxValue,
            math.Pi,
            math.E,
            Double.PositiveInfinity,
            Double.NegativeInfinity,
            Double.NaN,
            1.23456789e10,
            -1.23456789e-10
          )
        assertTrue(values.forall(v => firstField(cfg)(_.emitDouble(v)) == java.lang.Double.toString(v)))
      }
    )

  private val emitCharSpec =
    suite("::emitChar")(
      test("writes a plain ASCII char verbatim") {
        assertTrue(firstField(cfg)(_.emitChar('a')) == "a")
      },
      test("quotes and doubles the quote char") {
        assertTrue(firstField(cfg)(_.emitChar('"')) == "\"\"\"\"")
      },
      test("quotes the delimiter char") {
        assertTrue(firstField(cfg)(_.emitChar(',')) == "\",\"")
      },
      test("quotes CR") {
        assertTrue(firstField(cfg)(_.emitChar('\r')) == "\"\r\"")
      },
      test("quotes LF") {
        assertTrue(firstField(cfg)(_.emitChar('\n')) == "\"\n\"")
      },
      test("writes a non-ASCII BMP char verbatim") {
        assertTrue(firstField(cfg)(_.emitChar('é')) == "é")
      },
    )

  private def stringMatrix(config: CsvConfig): TestResult = {
    val cases: Seq[(String, String)] = Seq(
      ""           -> "",
      "hello"      -> "hello",
      "a,b"        -> s"${config.quoteChar}a,b${config.quoteChar}",
      "a\"b"       -> s"${config.quoteChar}a${config.quoteChar}${config.quoteChar}b${config.quoteChar}",
      "a\nb"       -> s"${config.quoteChar}a\nb${config.quoteChar}",
      "a\rb"       -> s"${config.quoteChar}a\rb${config.quoteChar}",
      "a\r\nb"     -> s"${config.quoteChar}a\r\nb${config.quoteChar}",
      "\"at start" -> s"${config.quoteChar}${config.quoteChar}${config.quoteChar}at start${config.quoteChar}",
      "at end\""   -> s"${config.quoteChar}at end${config.quoteChar}${config.quoteChar}${config.quoteChar}",
    )
    val defaults                     = cases.filter { case (in, _) =>
      if (config.delimiter == ',' && config.quoteChar == '"') true
      else !in.contains(',') && !in.contains('"')
    }
    assertTrue(defaults.forall { case (in, expected) => firstField(config)(_.emitString(in)) == expected })
  }

  private val emitStringSpec =
    suite("::emitString")(
      test("default config: matrix of escape cases") {
        stringMatrix(CsvConfig.default)
      },
      test("TSV+hash config quotes delimiter/quote correctly") {
        val tsv = CsvConfig(delimiter = '\t', quoteChar = '#')
        val out = firstField(tsv)(_.emitString("a\tb#c"))
        assertTrue(out == "#a\tb##c#")
      },
      test("LF-only terminator: string values pass through unchanged") {
        val lf = CsvConfig(lineTerminator = "\n")
        assertTrue(firstField(lf)(_.emitString("plain")) == "plain")
      },
      test("fast path on long plain string is identity") {
        val big = "a" * 100000
        assertTrue(firstField(cfg)(_.emitString(big)) == big)
      },
      test("preserves unicode (CJK and emoji)") {
        val s = "日本語 🎉 →"
        assertTrue(firstField(cfg)(_.emitString(s)) == s)
      },
      test("handles special at position 0, middle, end") {
        val inputs = Seq("\"abc", "ab\"cd", "abc\"")
        val outs   = inputs.map(s => firstField(cfg)(_.emitString(s)))
        assertTrue(outs == Seq("\"\"\"abc\"", "\"ab\"\"cd\"", "\"abc\"\"\""))
      },
    )

  private val emitEmptySpec =
    suite("::emitEmpty")(
      test("two in a row produce a lone delimiter") {
        val out = firstField(cfg) { e => e.emitEmpty(); e.emitEmpty() }
        assertTrue(out == ",")
      },
      test("one on a row produces an empty cell") {
        val out = firstField(cfg)(_.emitEmpty())
        assertTrue(out == "")
      },
    )

  private val delimiterPlacementSpec =
    suite("delimiter placement")(
      test("no delimiter before first field") {
        assertTrue(firstField(cfg)(_.emitInt(1)) == "1")
      },
      test("exactly one delimiter between two emits of different types") {
        val pairs: Seq[(String, FieldEmitter => Unit)] = Seq(
          "1,2"    -> (e => { e.emitInt(1); e.emitInt(2) }),
          "1,a"    -> (e => { e.emitInt(1); e.emitString("a") }),
          "a,1"    -> (e => { e.emitString("a"); e.emitInt(1) }),
          ",1"     -> (e => { e.emitEmpty(); e.emitInt(1) }),
          "1,"     -> (e => { e.emitInt(1); e.emitEmpty() }),
          "c,true" -> (e => { e.emitChar('c'); e.emitBoolean(true) }),
          "1,2"    -> (e => { e.emitShort(1.toShort); e.emitByte(2.toByte) }),
        )
        assertTrue(pairs.forall { case (exp, body) => firstField(cfg)(body) == exp })
      },
      test("three-field row") {
        val out = firstField(cfg) { e => e.emitInt(1); e.emitString("a"); e.emitBoolean(false) }
        assertTrue(out == "1,a,false")
      },
    )

  private val emitOptionSpec =
    suite("::emit(Option[A])")(
      test("Some(value) writes the encoded inner value (String)") {
        assertTrue(firstField(cfg)(_.emit(Some("hello"))) == "hello")
      },
      test("Some(value) writes the encoded inner value (Int)") {
        assertTrue(firstField(cfg)(_.emit(Some(42))) == "42")
      },
      test("Some(value) writes the encoded inner value (Boolean)") {
        assertTrue(firstField(cfg)(_.emit(Some(true))) == "true")
      },
      test("None writes an empty cell") {
        assertTrue(firstField(cfg)(_.emit(Option.empty[String])) == "")
      },
      test("Some quotes/escapes the inner value via its encoder") {
        // "a,b" needs quoting per the default CSV config — emit(Some(...)) must
        // route through emitString, not bypass the escaping logic.
        assertTrue(firstField(cfg)(_.emit(Some("a,b"))) == "\"a,b\"")
      },
      test("delimiter placement: None then Some, Some then None") {
        val noneThenSome =
          firstField(cfg) { e => e.emit(Option.empty[Int]); e.emit(Some(1)) }
        val someThenNone =
          firstField(cfg) { e => e.emit(Some(1)); e.emit(Option.empty[Int]) }
        assertTrue(noneThenSome == ",1", someThenNone == "1,")
      },
      test("works for any type with a CsvFieldEncoder (UUID)") {
        val u = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
        assertTrue(firstField(cfg)(_.emit(Some(u))) == u.toString)
      },
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("FieldEmitter")(
      emitIntSpec,
      emitLongSpec,
      emitShortSpec,
      emitByteSpec,
      emitBooleanSpec,
      emitFloatSpec,
      emitDoubleSpec,
      emitCharSpec,
      emitStringSpec,
      emitEmptySpec,
      emitOptionSpec,
      delimiterPlacementSpec,
      test("flushCount is reachable and starts at 0") {
        val sw = new java.io.StringWriter
        val w  = CsvWriter.unsafeFromWriter(sw, CsvConfig.default)
        w.writeRow { e =>
          e.emitInt(1)
          e.emitInt(2)
        }
        // P0 stub: always 0 against the Writer-backed implementation. P1 redefines
        // this to count byte-buffer flushes; the test will assert real numbers then.
        assertTrue(w.emitter.flushCount == 0L)
      },
    )
}
