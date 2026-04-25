package com.guizmaii.csvzen.core

import zio.Scope
import zio.test.*

import java.io.StringWriter
import java.time.*
import java.util.{Currency, UUID}

object CsvFieldEncoderSpec extends ZIOSpecDefault {

  private def encodeOne[A](a: A)(using enc: CsvFieldEncoder[A]): String = {
    val sw = new StringWriter
    val w  = CsvWriter.unsafeFromWriter(sw, CsvConfig.default)
    try w.writeRow(e => enc.encode(a, e))
    finally w.close()
    sw.toString.stripSuffix("\r\n")
  }

  private val primitives =
    suite("primitive encoders")(
      test("String nominal and empty") {
        assertTrue(
          encodeOne("hello") == "hello",
          encodeOne("") == "",
          encodeOne("with,comma") == "\"with,comma\"",
        )
      },
      test("Int boundaries") {
        assertTrue(
          encodeOne(0) == "0",
          encodeOne(Int.MaxValue) == Int.MaxValue.toString,
          encodeOne(Int.MinValue) == Int.MinValue.toString,
        )
      },
      test("Long boundaries") {
        assertTrue(
          encodeOne(Long.MaxValue) == Long.MaxValue.toString,
          encodeOne(Long.MinValue) == Long.MinValue.toString,
        )
      },
      test("Short boundaries") {
        assertTrue(
          encodeOne(Short.MaxValue) == Short.MaxValue.toString,
          encodeOne(Short.MinValue) == Short.MinValue.toString,
        )
      },
      test("Byte boundaries") {
        assertTrue(
          encodeOne(Byte.MaxValue) == Byte.MaxValue.toString,
          encodeOne(Byte.MinValue) == Byte.MinValue.toString,
        )
      },
      test("Boolean") {
        assertTrue(encodeOne(true) == "true", encodeOne(false) == "false")
      },
      test("Char plain and special") {
        assertTrue(encodeOne('a') == "a", encodeOne(',') == "\",\"", encodeOne('"') == "\"\"\"\"")
      },
      test("Float") {
        assertTrue(encodeOne(1.5f) == "1.5", encodeOne(0.0f) == "0.0")
      },
      test("Double") {
        assertTrue(encodeOne(1.5) == "1.5", encodeOne(math.Pi) == math.Pi.toString)
      },
    )

  private val carveOutValues =
    suite("carve-out encoders (BigInt, BigDecimal, UUID, Currency)")(
      test("BigInt") {
        assertTrue(encodeOne(BigInt("123456789012345678901234567890")) == "123456789012345678901234567890")
      },
      test("BigDecimal with scale") {
        val bd = BigDecimal("3.14159265358979323846")
        assertTrue(encodeOne(bd) == bd.toString)
      },
      test("UUID random and deterministic") {
        val u1 = UUID.randomUUID()
        val u2 = UUID.nameUUIDFromBytes("csvzen".getBytes("UTF-8"))
        assertTrue(encodeOne(u1) == u1.toString, encodeOne(u2) == u2.toString)
      },
      test("Currency codes") {
        val ok = Seq("EUR", "USD", "JPY").forall { code =>
          encodeOne(Currency.getInstance(code)) == code
        }
        assertTrue(ok)
      },
    )

  private val javaTimeValues =
    suite("java.time encoders")(
      test("DayOfWeek covers every value") {
        assertTrue(DayOfWeek.values.toSeq.forall(d => encodeOne(d) == d.toString))
      },
      test("Month covers every value") {
        assertTrue(Month.values.toSeq.forall(m => encodeOne(m) == m.toString))
      },
      test("Duration canonical form") {
        val d = Duration.ofSeconds(125, 500)
        assertTrue(encodeOne(d) == d.toString)
      },
      test("Instant near epoch and nanosecond precision") {
        val i = Instant.parse("2026-04-24T12:00:00.123456789Z")
        assertTrue(encodeOne(i) == i.toString)
      },
      test("LocalDate, LocalDateTime, LocalTime") {
        val d  = LocalDate.of(2026, 4, 24)
        val dt = LocalDateTime.of(2026, 4, 24, 12, 0, 0, 123456789)
        val t  = LocalTime.of(12, 0, 0, 123456789)
        assertTrue(encodeOne(d) == d.toString, encodeOne(dt) == dt.toString, encodeOne(t) == t.toString)
      },
      test("MonthDay, Period, Year, YearMonth") {
        val md = MonthDay.of(4, 24)
        val p  = Period.of(1, 2, 3)
        val y  = Year.of(2026)
        val ym = YearMonth.of(2026, 4)
        assertTrue(
          encodeOne(md) == md.toString,
          encodeOne(p) == p.toString,
          encodeOne(y) == y.toString,
          encodeOne(ym) == ym.toString,
        )
      },
      test("OffsetDateTime, OffsetTime") {
        val odt = OffsetDateTime.parse("2026-04-24T12:00:00+02:00")
        val ot  = OffsetTime.parse("12:00:00+02:00")
        assertTrue(encodeOne(odt) == odt.toString, encodeOne(ot) == ot.toString)
      },
      test("ZoneId, ZoneOffset, ZonedDateTime") {
        val z   = ZoneId.of("Europe/Paris")
        val zo  = ZoneOffset.ofHours(-5)
        val zdt = ZonedDateTime.parse("2026-04-24T12:00:00+02:00[Europe/Paris]")
        assertTrue(encodeOne(z) == z.toString, encodeOne(zo) == zo.toString, encodeOne(zdt) == zdt.toString)
      },
    )

  private val optionSpec =
    suite("Option encoder")(
      test("None emits empty cell") {
        assertTrue(encodeOne[Option[Int]](None) == "")
      },
      test("Some delegates to inner encoder") {
        assertTrue(encodeOne[Option[Int]](Some(42)) == "42", encodeOne[Option[String]](Some("hi")) == "hi")
      },
      test("Some with String containing specials is quoted") {
        assertTrue(encodeOne[Option[String]](Some("a,b")) == "\"a,b\"")
      },
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("CsvFieldEncoder")(primitives, carveOutValues, javaTimeValues, optionSpec)
}
