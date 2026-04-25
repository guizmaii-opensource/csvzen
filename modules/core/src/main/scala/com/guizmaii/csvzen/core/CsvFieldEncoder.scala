package com.guizmaii.csvzen.core

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

trait CsvFieldEncoder[-A] {
  def encode(a: A, out: FieldEmitter): Unit
}

object CsvFieldEncoder {

  given stringEncoder: CsvFieldEncoder[String]   = (a, out) => out.emitString(a)
  given intEncoder: CsvFieldEncoder[Int]         = (a, out) => out.emitInt(a)
  given longEncoder: CsvFieldEncoder[Long]       = (a, out) => out.emitLong(a)
  given shortEncoder: CsvFieldEncoder[Short]     = (a, out) => out.emitShort(a)
  given byteEncoder: CsvFieldEncoder[Byte]       = (a, out) => out.emitByte(a)
  given booleanEncoder: CsvFieldEncoder[Boolean] = (a, out) => out.emitBoolean(a)
  given charEncoder: CsvFieldEncoder[Char]       = (a, out) => out.emitChar(a)

  given floatEncoder: CsvFieldEncoder[Float]           = (a, out) => out.emitFloat(a)
  given doubleEncoder: CsvFieldEncoder[Double]         = (a, out) => out.emitDouble(a)
  given bigIntEncoder: CsvFieldEncoder[BigInt]         = (a, out) => out.emitString(a.toString)
  given bigDecimalEncoder: CsvFieldEncoder[BigDecimal] = (a, out) => out.emitString(a.toString)
  given uuidEncoder: CsvFieldEncoder[UUID]             = (a, out) => out.emitString(a.toString)
  given currencyEncoder: CsvFieldEncoder[Currency]     = (a, out) => out.emitString(a.getCurrencyCode)

  given dayOfWeekEncoder: CsvFieldEncoder[DayOfWeek]           = (a, out) => out.emitString(a.toString)
  given durationEncoder: CsvFieldEncoder[Duration]             = (a, out) => out.emitString(a.toString)
  given instantEncoder: CsvFieldEncoder[Instant]               = (a, out) => out.emitString(a.toString)
  given localDateEncoder: CsvFieldEncoder[LocalDate]           = (a, out) => out.emitString(a.toString)
  given localDateTimeEncoder: CsvFieldEncoder[LocalDateTime]   = (a, out) => out.emitString(a.toString)
  given localTimeEncoder: CsvFieldEncoder[LocalTime]           = (a, out) => out.emitString(a.toString)
  given monthEncoder: CsvFieldEncoder[Month]                   = (a, out) => out.emitString(a.toString)
  given monthDayEncoder: CsvFieldEncoder[MonthDay]             = (a, out) => out.emitString(a.toString)
  given offsetDateTimeEncoder: CsvFieldEncoder[OffsetDateTime] = (a, out) => out.emitString(a.toString)
  given offsetTimeEncoder: CsvFieldEncoder[OffsetTime]         = (a, out) => out.emitString(a.toString)
  given periodEncoder: CsvFieldEncoder[Period]                 = (a, out) => out.emitString(a.toString)
  given yearEncoder: CsvFieldEncoder[Year]                     = (a, out) => out.emitString(a.toString)
  given yearMonthEncoder: CsvFieldEncoder[YearMonth]           = (a, out) => out.emitString(a.toString)
  given zoneIdEncoder: CsvFieldEncoder[ZoneId]                 = (a, out) => out.emitString(a.toString)
  given zoneOffsetEncoder: CsvFieldEncoder[ZoneOffset]         = (a, out) => out.emitString(a.toString)
  given zonedDateTimeEncoder: CsvFieldEncoder[ZonedDateTime]   = (a, out) => out.emitString(a.toString)

  given optionEncoder[A](using inner: CsvFieldEncoder[A]): CsvFieldEncoder[Option[A]] = { (a, out) =>
    a match {
      case Some(v) => inner.encode(v, out)
      case None    => out.emitEmpty()
    }
  }
}
