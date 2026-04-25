package com.guizmaii.csvzen.testkit

import com.guizmaii.csvzen.core.CsvRowEncoder
import zio.Scope
import zio.test.*

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

object GoldenSpec extends ZIOSpecDefault {

  // We hand-roll the generators below instead of `DeriveGen[A]` for two reasons:
  //
  //   1. **Clean rendering.** `DeriveGen[String]` reaches for the full `Char`
  //      range, including RTL Arabic / surrogate-pair / control characters.
  //      Those produce technically-valid but visually-jumbled goldens (Unicode
  //      bidi reorders "name,number" mid-cell when the name contains an
  //      Arabic char). We use `Gen.alphaNumericString` / `Gen.alphaNumericChar`
  //      to keep golden files readable in IDEs without sacrificing
  //      stable-snapshot coverage.
  //
  //   2. **No DeriveGen instances for non-stdlib types.** zio-test-magnolia's
  //      `DeriveGen` ships generators for primitives, `String`, `Option`, etc.,
  //      but not for `BigInt` / `BigDecimal` / `UUID` / `Currency` / any
  //      `java.time.*`. For `TotalRecord` we'd have to provide most of them by
  //      hand anyway, so we just hand-roll the whole record for consistency.
  //
  // The csvzen encoder itself is exercised against the full Unicode / boundary
  // ranges by `core/src/test/.../FieldEmitterSpec.scala` and `CsvFileWriterSpec.scala`;
  // these golden tests are about end-to-end stability, not exhaustion.

  // --- Smoke-test record (small, easy to eyeball in a diff). ---------------
  final case class SimpleRecord(id: Int, name: String, active: Boolean) derives CsvRowEncoder

  private val simpleRecordGen: Gen[Sized, SimpleRecord] =
    for {
      id     <- Gen.int
      name   <- Gen.alphaNumericString
      active <- Gen.boolean
    } yield SimpleRecord(id, name, active)

  // --- Coverage record: every type csvzen ships an encoder for. ------------
  final case class TotalRecord(
    string: String,
    int: Int,
    long: Long,
    short: Short,
    byte: Byte,
    boolean: Boolean,
    char: Char,
    float: Float,
    double: Double,
    bigInt: BigInt,
    bigDecimal: BigDecimal,
    uuid: UUID,
    currency: Currency,
    dayOfWeek: DayOfWeek,
    duration: Duration,
    instant: Instant,
    localDate: LocalDate,
    localDateTime: LocalDateTime,
    localTime: LocalTime,
    month: Month,
    monthDay: MonthDay,
    offsetDateTime: OffsetDateTime,
    offsetTime: OffsetTime,
    period: Period,
    year: Year,
    yearMonth: YearMonth,
    zoneId: ZoneId,
    zoneOffset: ZoneOffset,
    zonedDateTime: ZonedDateTime,
    optionString: Option[String],
  ) derives CsvRowEncoder

  // Bounded sub-generators producing values that render cleanly in IDEs and
  // stay within sensible ranges (no NaN/Infinity, recognisable dates, etc.).

  private val finiteFloat: Gen[Any, Float] =
    Gen.float.filter(f => !f.isNaN && !f.isInfinite)

  private val finiteDouble: Gen[Any, Double] =
    Gen.double.filter(d => !d.isNaN && !d.isInfinite)

  private val currencyGen: Gen[Any, Currency] =
    Gen.elements("EUR", "USD", "JPY", "GBP", "AUD").map(Currency.getInstance)

  private val zoneIdGen: Gen[Any, ZoneId] =
    Gen.elements("UTC", "Europe/Paris", "America/New_York", "Asia/Tokyo", "Australia/Sydney").map(ZoneId.of)

  private val zoneOffsetGen: Gen[Any, ZoneOffset] =
    Gen.int(-18 * 3600, 18 * 3600).map(ZoneOffset.ofTotalSeconds)

  // Roughly [1970-01-01, 2100-01-01].
  private val instantGen: Gen[Any, Instant] =
    Gen.long(0L, 4_102_444_800L).map(Instant.ofEpochSecond)

  private val localDateGen: Gen[Any, LocalDate] =
    Gen.long(0L, 50_000L).map(LocalDate.ofEpochDay)

  private val localTimeGen: Gen[Any, LocalTime] =
    Gen.long(0L, 86_399_999_999_999L).map(LocalTime.ofNanoOfDay)

  private val localDateTimeGen: Gen[Any, LocalDateTime] =
    instantGen.zipWith(zoneOffsetGen)(LocalDateTime.ofInstant(_, _))

  private val offsetDateTimeGen: Gen[Any, OffsetDateTime] =
    instantGen.zipWith(zoneOffsetGen)(OffsetDateTime.ofInstant(_, _))

  private val offsetTimeGen: Gen[Any, OffsetTime] =
    localTimeGen.zipWith(zoneOffsetGen)(OffsetTime.of)

  private val zonedDateTimeGen: Gen[Any, ZonedDateTime] =
    instantGen.zipWith(zoneIdGen)(ZonedDateTime.ofInstant(_, _))

  private val durationGen: Gen[Any, Duration] =
    Gen.long(-86_400L, 86_400L).map(Duration.ofSeconds)

  private val periodGen: Gen[Any, Period] =
    for {
      y <- Gen.int(0, 100)
      m <- Gen.int(0, 11)
      d <- Gen.int(0, 30)
    } yield Period.of(y, m, d)

  private val yearGen: Gen[Any, Year] =
    Gen.int(1970, 2100).map(Year.of)

  private val yearMonthGen: Gen[Any, YearMonth] =
    yearGen.zipWith(Gen.elements(Month.values()*))(_.atMonth(_))

  private val monthDayGen: Gen[Any, MonthDay] =
    Gen.elements(Month.values()*).flatMap(m => Gen.int(1, m.minLength).map(MonthDay.of(m, _)))

  private val totalRecordGen: Gen[Sized, TotalRecord] =
    for {
      string         <- Gen.alphaNumericString
      int            <- Gen.int
      long           <- Gen.long
      short          <- Gen.short
      byte           <- Gen.byte
      boolean        <- Gen.boolean
      char           <- Gen.alphaNumericChar
      float          <- finiteFloat
      double         <- finiteDouble
      bigInt         <- Gen.long.map(BigInt(_))
      bigDecimal     <- finiteDouble.map(BigDecimal(_))
      uuid           <- Gen.uuid
      currency       <- currencyGen
      dayOfWeek      <- Gen.elements(DayOfWeek.values()*)
      duration       <- durationGen
      instant        <- instantGen
      localDate      <- localDateGen
      localDateTime  <- localDateTimeGen
      localTime      <- localTimeGen
      month          <- Gen.elements(Month.values()*)
      monthDay       <- monthDayGen
      offsetDateTime <- offsetDateTimeGen
      offsetTime     <- offsetTimeGen
      period         <- periodGen
      year           <- yearGen
      yearMonth      <- yearMonthGen
      zoneId         <- zoneIdGen
      zoneOffset     <- zoneOffsetGen
      zonedDateTime  <- zonedDateTimeGen
      optionString   <- Gen.option(Gen.alphaNumericString)
    } yield TotalRecord(
      string,
      int,
      long,
      short,
      byte,
      boolean,
      char,
      float,
      double,
      bigInt,
      bigDecimal,
      uuid,
      currency,
      dayOfWeek,
      duration,
      instant,
      localDate,
      localDateTime,
      localTime,
      month,
      monthDay,
      offsetDateTime,
      offsetTime,
      period,
      year,
      yearMonth,
      zoneId,
      zoneOffset,
      zonedDateTime,
      optionString,
    )

  private val nestedConfig: GoldenConfiguration =
    GoldenConfiguration.default.copy(relativePath = "nested")

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("GoldenSpec")(
      csvGoldenTest(simpleRecordGen),
      csvGoldenTest(totalRecordGen),
      // Verifies that GoldenConfiguration.relativePath disambiguates same-named
      // types: this golden lives under `golden/nested/SimpleRecord.csv`.
      csvGoldenTest(simpleRecordGen, nestedConfig),
    )
}
