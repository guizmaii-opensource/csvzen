package com.guizmaii.csvzen.bench

import com.guizmaii.csvzen.core.CsvRowEncoder

import java.time.Instant

/**
 * Four flat case classes used as benchmark fixtures. All five-field to keep
 * row size comparable across schemas. `derives CsvRowEncoder` gives each one
 * an encoder whose header names match the field labels.
 */
object Schemas {

  final case class Mixed(
    id: Long,
    name: String,
    count: Int,
    amount: Double,
    ts: Instant,
  ) derives CsvRowEncoder

  final case class IntHeavy(
    a: Long,
    b: Long,
    c: Long,
    d: Long,
    e: Long,
  ) derives CsvRowEncoder

  final case class DoubleHeavy(
    a: Double,
    b: Double,
    c: Double,
    d: Double,
    e: Double,
  ) derives CsvRowEncoder

  /**
   * Five string fields. By construction half of each batch (every other row)
   * contains a comma so the encoder hits the quoting path; the other half is
   * plain ASCII alphanumeric so the encoder hits the fast path. ASCII-Latin
   * only — `DeriveGen[String]` reaches into the full Unicode range and
   * surrogate pairs, which would put us on the multi-byte UTF-8 path *and*
   * make IDE-side diffs unreadable. We want clean, reproducible bytes here.
   */
  final case class StringHeavy(
    a: String,
    b: String,
    c: String,
    d: String,
    e: String,
  ) derives CsvRowEncoder
}
