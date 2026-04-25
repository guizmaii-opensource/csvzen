package com.guizmaii.csvzen.core

import zio.Scope
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.{
  DayOfWeek,
  Duration,
  Instant,
  LocalDate,
  LocalDateTime,
  LocalTime,
  Month,
  MonthDay,
  OffsetDateTime,
  OffsetTime,
  Period,
  Year,
  YearMonth,
  ZoneId,
  ZoneOffset,
  ZonedDateTime,
}
import java.util.{Currency, UUID}

object CsvFileWriterSpec extends ZIOSpecDefault {

  private def withTmpFile[A](body: Path => A): A = {
    val p = Files.createTempFile("csvzen-", ".csv")
    try body(p)
    finally { val _ = Files.deleteIfExists(p) }
  }

  private def writeAndRead(
    config: CsvConfig = CsvConfig.default,
  )(body: CsvWriter => Unit): (String, Array[Byte]) =
    withTmpFile { p =>
      val w     = CsvWriter.open(p, config)
      try body(w)
      finally w.close()
      val str   = Files.readString(p, StandardCharsets.UTF_8)
      val bytes = Files.readAllBytes(p)
      (str, bytes)
    }

  private val utf8Spec =
    suite("UTF-8 correctness")(
      test("writes emoji, CJK, and accented chars as valid UTF-8") {
        val (s, _) = writeAndRead() { w =>
          w.writeRow(_.emitString("é日本語🎉→"))
        }
        assertTrue(s == "é日本語🎉→\r\n")
      },
      test("emoji round-trip: exact byte sequence matches UTF-8 encoding") {
        val text           = "🎉"
        val (_, bytes)     = writeAndRead() { w =>
          w.writeRow(_.emitString(text))
        }
        val expectedPrefix = text.getBytes(StandardCharsets.UTF_8)
        val got            = bytes.take(expectedPrefix.length)
        val matches        = got.sameElements(expectedPrefix)
        assertTrue(matches)
      },
      test("no BOM is written") {
        val (_, bytes) = writeAndRead() { w =>
          w.writeRow(_.emitString("a"))
        }
        assertTrue(bytes(0) == 'a'.toByte)
      },
    )

  private val terminatorSpec =
    suite("line terminators")(
      test("default \\r\\n at end of each row") {
        val (_, bytes) = writeAndRead() { w =>
          w.writeRow(_.emitString("a"))
        }
        val last       = bytes.takeRight(2)
        assertTrue(last(0) == 0x0d.toByte, last(1) == 0x0a.toByte)
      },
      test("LF-only terminator writes exactly 0x0A") {
        val cfg        = CsvConfig(lineTerminator = "\n")
        val (_, bytes) = writeAndRead(cfg) { w =>
          w.writeRow(_.emitString("a"))
        }
        assertTrue(bytes.last == 0x0a.toByte, !bytes.contains(0x0d.toByte))
      },
      test("no stray terminator appended beyond what rows emitted") {
        val (s, _) = writeAndRead() { w =>
          w.writeRow(_.emitString("x"))
          w.writeRow(_.emitString("y"))
        }
        assertTrue(s == "x\r\ny\r\n")
      },
    )

  private val emptyAndHeaderSpec =
    suite("empty and header-only files")(
      test("empty file: open + close with no writes produces 0-byte file") {
        val (_, bytes) = writeAndRead()(_ => ())
        assertTrue(bytes.length == 0)
      },
      test("header-only file: exactly one terminated line") {
        final case class H(a: Int, b: String) derives CsvRowEncoder
        val (s, _) = writeAndRead()(w => w.writeHeader[H]())
        assertTrue(s == "a,b\r\n")
      },
    )

  private val largeFileSpec = {
    final case class Big(id: Int, name: String, flag: Boolean, note: Option[String]) derives CsvRowEncoder
    suite("large file")(
      test("100k mixed-type rows round-trip correctly (line count + sentinels)") {
        val n      = 100_000
        val (s, _) = writeAndRead() { w =>
          w.writeHeader[Big]()
          var i = 0
          while (i < n) {
            val note = if (i % 7 == 0) Some("with,comma") else None
            w.writeRow(Big(i, s"r$i", (i % 2) == 0, note))
            i += 1
          }
        }
        val parsed = TestCsvReader.parse(s)
        assertTrue(
          parsed.length == n + 1,
          parsed.head == Vector("id", "name", "flag", "note"),
          parsed(1) == Vector("0", "r0", "true", "with,comma"),
          parsed.last == Vector((n - 1).toString, s"r${n - 1}", ((n - 1) % 2 == 0).toString, ""),
        )
      }
    )
  }

  private val quotingRoundTripSpec =
    suite("quoting round-trip via TestCsvReader")(
      test("parse-back matrix of escape cases") {
        val inputs = Seq(
          "plain",
          "",
          "a,b",
          "a\"b",
          "a\nb",
          "a\rb",
          "a\r\nb",
          "\"starts",
          "ends\"",
          "double \"\" inside",
          "日本語",
          "mixed,\"and\"\nstuff",
        )
        val (s, _) = writeAndRead() { w =>
          inputs.foreach(in => w.writeRow(_.emitString(in)))
        }
        val parsed = TestCsvReader.parse(s)
        assertTrue(parsed == inputs.map(Vector(_)).toVector)
      },
      test("TSV with hash quote round-trips") {
        val cfg    = CsvConfig(delimiter = '\t', quoteChar = '#')
        val inputs = Seq("a\tb", "c#d", "normal", "")
        val (s, _) = writeAndRead(cfg) { w =>
          inputs.foreach(in => w.writeRow(_.emitString(in)))
        }
        val parsed = TestCsvReader.parse(s, cfg)
        assertTrue(parsed == inputs.map(Vector(_)).toVector)
      },
    )

  private val overwriteAndMissingDirSpec =
    suite("file-level semantics")(
      test("opening on an existing file truncates") {
        withTmpFile { p =>
          Files.writeString(p, "PREEXISTING CONTENT THAT SHOULD BE GONE\r\n", StandardCharsets.UTF_8)
          val w    = CsvWriter.open(p, CsvConfig.default)
          try w.writeRow(_.emitString("new"))
          finally w.close()
          val read = Files.readString(p, StandardCharsets.UTF_8)
          assertTrue(read == "new\r\n")
        }
      },
      test("missing parent directory raises IOException") {
        val tmpDir = Files.createTempDirectory("csvzen-missing-")
        try {
          val missing = tmpDir.resolve("does-not-exist").resolve("out.csv")
          val ex      = scala.util.Try(CsvWriter.open(missing, CsvConfig.default))
          val failed  = ex.isFailure
          val isIoExc = ex.failed.toOption.exists(_.isInstanceOf[java.io.IOException])
          assertTrue(failed, isIoExc)
        } finally { val _ = Files.deleteIfExists(tmpDir) }
      },
    )

  // Each shipped primitive round-tripped through a real file.
  private final case class P1(v: String) derives CsvRowEncoder
  private final case class P2(v: Int) derives CsvRowEncoder
  private final case class P3(v: Long) derives CsvRowEncoder
  private final case class P4(v: Short) derives CsvRowEncoder
  private final case class P5(v: Byte) derives CsvRowEncoder
  private final case class P6(v: Boolean) derives CsvRowEncoder
  private final case class P7(v: Char) derives CsvRowEncoder
  private final case class P8(v: Float) derives CsvRowEncoder
  private final case class P9(v: Double) derives CsvRowEncoder
  private final case class P10(v: BigInt) derives CsvRowEncoder
  private final case class P11(v: BigDecimal) derives CsvRowEncoder
  private final case class P12(v: UUID) derives CsvRowEncoder
  private final case class P13(v: Currency) derives CsvRowEncoder
  private final case class P14(v: DayOfWeek) derives CsvRowEncoder
  private final case class P15(v: Duration) derives CsvRowEncoder
  private final case class P16(v: Instant) derives CsvRowEncoder
  private final case class P17(v: LocalDate) derives CsvRowEncoder
  private final case class P18(v: LocalDateTime) derives CsvRowEncoder
  private final case class P19(v: LocalTime) derives CsvRowEncoder
  private final case class P20(v: Month) derives CsvRowEncoder
  private final case class P21(v: MonthDay) derives CsvRowEncoder
  private final case class P22(v: OffsetDateTime) derives CsvRowEncoder
  private final case class P23(v: OffsetTime) derives CsvRowEncoder
  private final case class P24(v: Period) derives CsvRowEncoder
  private final case class P25(v: Year) derives CsvRowEncoder
  private final case class P26(v: YearMonth) derives CsvRowEncoder
  private final case class P27(v: ZoneId) derives CsvRowEncoder
  private final case class P28(v: ZoneOffset) derives CsvRowEncoder
  private final case class P29(v: ZonedDateTime) derives CsvRowEncoder

  private def parseFirstCell[A](row: A)(using enc: CsvRowEncoder[A]): String = {
    val (s, _) = writeAndRead()(_.writeRow(row))
    TestCsvReader.parse(s).head.head
  }

  private val roundTripAllEncoders =
    suite("round-trip each shipped encoder through a real file")(
      test("String (with specials)") {
        val cell = parseFirstCell(P1("a,\"b\nc"))
        assertTrue(cell == "a,\"b\nc")
      },
      test("Int / Long / Short / Byte") {
        val intVal   = Integer.parseInt(parseFirstCell(P2(Int.MaxValue)))
        val longVal  = java.lang.Long.parseLong(parseFirstCell(P3(Long.MinValue)))
        val shortVal = java.lang.Short.parseShort(parseFirstCell(P4(Short.MaxValue)))
        val byteVal  = java.lang.Byte.parseByte(parseFirstCell(P5((-1).toByte)))
        assertTrue(
          intVal == Int.MaxValue,
          longVal == Long.MinValue,
          shortVal == Short.MaxValue,
          byteVal == (-1).toByte,
        )
      },
      test("Boolean / Char") {
        val boolCell = parseFirstCell(P6(true)).toBoolean
        val charCell = parseFirstCell(P7('z'))
        assertTrue(boolCell, charCell == "z")
      },
      test("Float / Double") {
        val fv = java.lang.Float.parseFloat(parseFirstCell(P8(1.5f)))
        val dv = java.lang.Double.parseDouble(parseFirstCell(P9(math.Pi)))
        assertTrue(fv == 1.5f, dv == math.Pi)
      },
      test("BigInt / BigDecimal") {
        val bi     = BigInt("123456789012345678901234567890")
        val bd     = BigDecimal("3.14159265358979323846")
        val biBack = BigInt(parseFirstCell(P10(bi)))
        val bdBack = BigDecimal(parseFirstCell(P11(bd)))
        assertTrue(biBack == bi, bdBack == bd)
      },
      test("UUID / Currency") {
        val u       = UUID.randomUUID()
        val uBack   = UUID.fromString(parseFirstCell(P12(u)))
        val curCell = parseFirstCell(P13(Currency.getInstance("EUR")))
        assertTrue(uBack == u, curCell == "EUR")
      },
      test("DayOfWeek / Month / Duration") {
        val dw     = DayOfWeek.FRIDAY
        val m      = Month.APRIL
        val d      = Duration.ofSeconds(125, 500)
        val dwBack = DayOfWeek.valueOf(parseFirstCell(P14(dw)))
        val mBack  = Month.valueOf(parseFirstCell(P20(m)))
        val dBack  = Duration.parse(parseFirstCell(P15(d)))
        assertTrue(dwBack == dw, mBack == m, dBack == d)
      },
      test("Instant / LocalDate / LocalDateTime / LocalTime") {
        val i      = Instant.parse("2026-04-24T12:00:00.123456789Z")
        val ld     = LocalDate.of(2026, 4, 24)
        val dt     = LocalDateTime.of(2026, 4, 24, 12, 0, 0, 123456789)
        val lt     = LocalTime.of(12, 0, 0, 123456789)
        val iBack  = Instant.parse(parseFirstCell(P16(i)))
        val ldBack = LocalDate.parse(parseFirstCell(P17(ld)))
        val dtBack = LocalDateTime.parse(parseFirstCell(P18(dt)))
        val ltBack = LocalTime.parse(parseFirstCell(P19(lt)))
        assertTrue(iBack == i, ldBack == ld, dtBack == dt, ltBack == lt)
      },
      test("MonthDay / Period / Year / YearMonth") {
        val md     = MonthDay.of(4, 24)
        val p      = Period.of(1, 2, 3)
        val y      = Year.of(2026)
        val ym     = YearMonth.of(2026, 4)
        val mdBack = MonthDay.parse(parseFirstCell(P21(md)))
        val pBack  = Period.parse(parseFirstCell(P24(p)))
        val yBack  = Year.parse(parseFirstCell(P25(y)))
        val ymBack = YearMonth.parse(parseFirstCell(P26(ym)))
        assertTrue(mdBack == md, pBack == p, yBack == y, ymBack == ym)
      },
      test("OffsetDateTime / OffsetTime") {
        val odt     = OffsetDateTime.parse("2026-04-24T12:00:00+02:00")
        val ot      = OffsetTime.parse("12:00:00+02:00")
        val odtBack = OffsetDateTime.parse(parseFirstCell(P22(odt)))
        val otBack  = OffsetTime.parse(parseFirstCell(P23(ot)))
        assertTrue(odtBack == odt, otBack == ot)
      },
      test("ZoneId / ZoneOffset / ZonedDateTime") {
        val z       = ZoneId.of("Europe/Paris")
        val zo      = ZoneOffset.ofHours(-5)
        val zdt     = ZonedDateTime.parse("2026-04-24T12:00:00+02:00[Europe/Paris]")
        val zBack   = ZoneId.of(parseFirstCell(P27(z)))
        val zoBack  = ZoneOffset.of(parseFirstCell(P28(zo)))
        val zdtBack = ZonedDateTime.parse(parseFirstCell(P29(zdt)))
        assertTrue(zBack == z, zoBack == zo, zdtBack == zdt)
      },
    )

  private val charsetAndOpenOptionsSpec =
    suite("CsvWriter.open charset and open-options")(
      test("ISO-8859-1: non-ASCII characters are encoded in that charset, not UTF-8") {
        withTmpFile { p =>
          val latin1   = java.nio.charset.Charset.forName("ISO-8859-1")
          val w        = CsvWriter.open(p, CsvConfig.default, latin1)
          try w.writeRow(_.emitString("café"))
          finally w.close()
          val bytes    = Files.readAllBytes(p)
          val expected = "café\r\n".getBytes(latin1)
          val matches  = bytes.sameElements(expected)
          assertTrue(matches)
        }
      },
      test("APPEND option appends to existing content") {
        withTmpFile { p =>
          Files.writeString(p, "existing\r\n", StandardCharsets.UTF_8)
          val w    = CsvWriter.open(
            p,
            CsvConfig.default,
            StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.APPEND,
          )
          try w.writeRow(_.emitString("added"))
          finally w.close()
          val read = Files.readString(p, StandardCharsets.UTF_8)
          assertTrue(read == "existing\r\nadded\r\n")
        }
      },
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("CsvFileWriter")(
      utf8Spec,
      terminatorSpec,
      emptyAndHeaderSpec,
      largeFileSpec,
      quotingRoundTripSpec,
      overwriteAndMissingDirSpec,
      charsetAndOpenOptionsSpec,
      roundTripAllEncoders,
    )
}
