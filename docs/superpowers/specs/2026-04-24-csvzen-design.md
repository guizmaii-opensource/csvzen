# csvzen — Design

**Date:** 2026-04-24
**Status:** Implemented in v0.1.0-SNAPSHOT
**Scope:** v1 of `csvzen-core` (JVM-only, Scala 3, writes only)

## 1. Purpose

A tiny Scala-3-only library for writing CSV files in a zero-allocation, streaming fashion. Positioned explicitly against:

- **scala-csv** — requires `Seq[String]` per row and relies on `.toString` for every cell.
- **kantan-csv** — codec concept is good, but heavy implicit machinery and per-row allocations.
- **zio-blocks `schema-csv`** — codec concept is good, but encoders `.toString` every cell, build per-row `StringBuilder`s, and drag in the full schema-reflection machinery.

csvzen keeps the codec concept and strips everything else: a typed emitter that writes primitives straight to a `java.io.Writer` with no intermediate `String`s, and a minimal typeclass layer on top for case-class ergonomics.

## 2. Scope & non-goals

**In scope (v1):**

- Writing RFC 4180-compliant CSV to a file.
- Zero allocations in the hot path for `String`, `Int`, `Long`, `Short`, `Byte`, `Boolean`, `Char`, and `Option[None]`.
- One documented `String` allocation per cell for `Float`, `Double`, `BigInt`, `BigDecimal`, `UUID`, `Currency`, and all shipped `java.time.*` types (same carve-out zio-blocks makes, on the same grounds: correct formatting without allocation is non-trivial).
- Typeclass-driven row writing: `CsvRowEncoder[A]` with `derives CsvRowEncoder` for flat product types.
- Headers derived from case-class labels.
- An imperative escape-hatch API equivalent to the existing prior internal baseline.

**Out of scope (v1):**

- Reading CSV. Encoders only; no `Decoder`/`Codec` symmetry.
- Nested case classes, sealed traits, tuples, sequences, maps.
- `fs2` integration, ever.
- `ZIO` integration (deferred to `csvzen-zio` — not part of v1).
- Scala.js, Scala Native (JVM-only).
- JMH micro-benchmarks.
- Thread-safety (documented single-threaded).
- Custom header names / annotation-based overrides (can be added non-breakingly later).

## 3. Module layout

Multi-module sbt build from the start, with a single live sub-project for v1:

```
csvzen/
  build.sbt                 (root aggregate)
  project/
  core/
    src/main/scala/com/guizmaii/csvzen/core/
    src/main/scala/com/guizmaii/csvzen/core/internal/
    src/test/scala/com/guizmaii/csvzen/core/
```

- Published artefact id: `csvzen-core`.
- Base package: `com.guizmaii.csvzen.core` (public API).
- Internals: `com.guizmaii.csvzen.core.internal` (not exported).
- No external runtime dependencies. Stdlib + JDK only.
- Test dependency: `dev.zio::zio-test` / `dev.zio::zio-test-sbt` (matches the broader workspace convention — no runtime impact).
- Scala 3.3.7; max line length 120 (existing project scalafmt assumed).
- Adding `csvzen-zio` later is a small `build.sbt` addition (`lazy val zio = project.dependsOn(core)`); no public-API changes required.

## 4. Public API

Everything a user sees from `csvzen-core`:

```scala
package com.guizmaii.csvzen.core

import java.nio.file.Path
import scala.deriving.Mirror

final case class CsvConfig(
  delimiter: Char = ',',
  quoteChar: Char = '"',
  lineTerminator: String = "\r\n",
)
object CsvConfig:
  val default: CsvConfig = CsvConfig()

final class CsvWriter extends AutoCloseable:
  inline def writeRow[A](a: A)(using enc: CsvRowEncoder[A]): Unit
  inline def writeRow(inline body: FieldEmitter => Unit): Unit
  inline def writeHeader[A]()(using enc: CsvRowEncoder[A]): Unit
  def writeHeader(names: IndexedSeq[String]): Unit
  inline def writeAll[A](rows: Iterable[A])(using enc: CsvRowEncoder[A]): Unit
  def close(): Unit

object CsvWriter:
  def open(path: Path, config: CsvConfig): CsvWriter

final class FieldEmitter:
  def emitString(s: String): Unit
  def emitInt(i: Int): Unit
  def emitLong(l: Long): Unit
  def emitShort(s: Short): Unit
  def emitByte(b: Byte): Unit
  def emitBoolean(b: Boolean): Unit
  def emitChar(c: Char): Unit
  def emitFloat(f: Float): Unit
  def emitDouble(d: Double): Unit
  def emitEmpty(): Unit

trait CsvFieldEncoder[-A]:
  def encode(a: A, out: FieldEmitter): Unit

object CsvFieldEncoder:
  given CsvFieldEncoder[String]
  given CsvFieldEncoder[Int]
  given CsvFieldEncoder[Long]
  given CsvFieldEncoder[Short]
  given CsvFieldEncoder[Byte]
  given CsvFieldEncoder[Boolean]
  given CsvFieldEncoder[Char]
  given CsvFieldEncoder[Float]
  given CsvFieldEncoder[Double]
  given CsvFieldEncoder[BigInt]
  given CsvFieldEncoder[BigDecimal]
  given CsvFieldEncoder[java.util.UUID]
  given CsvFieldEncoder[java.util.Currency]
  given CsvFieldEncoder[java.time.DayOfWeek]
  given CsvFieldEncoder[java.time.Duration]
  given CsvFieldEncoder[java.time.Instant]
  given CsvFieldEncoder[java.time.LocalDate]
  given CsvFieldEncoder[java.time.LocalDateTime]
  given CsvFieldEncoder[java.time.LocalTime]
  given CsvFieldEncoder[java.time.Month]
  given CsvFieldEncoder[java.time.MonthDay]
  given CsvFieldEncoder[java.time.OffsetDateTime]
  given CsvFieldEncoder[java.time.OffsetTime]
  given CsvFieldEncoder[java.time.Period]
  given CsvFieldEncoder[java.time.Year]
  given CsvFieldEncoder[java.time.YearMonth]
  given CsvFieldEncoder[java.time.ZoneId]
  given CsvFieldEncoder[java.time.ZoneOffset]
  given CsvFieldEncoder[java.time.ZonedDateTime]
  given [A](using CsvFieldEncoder[A]): CsvFieldEncoder[Option[A]]

trait CsvRowEncoder[-A]:
  def headerNames: IndexedSeq[String]
  def encode(a: A, out: FieldEmitter): Unit

object CsvRowEncoder:
  inline def derived[A](using m: Mirror.ProductOf[A]): CsvRowEncoder[A]
```

**Typical usage:**

```scala
import com.guizmaii.csvzen.core.*
import scala.util.Using

final case class Person(name: String, age: Int, email: Option[String])
  derives CsvRowEncoder

Using.resource(CsvWriter.open(path, CsvConfig.default)) { w =>
  w.writeHeader[Person]()
  w.writeAll(people)
}
```

## 5. `FieldEmitter` internals

A single `FieldEmitter` instance per `CsvWriter`, reused for every row.

**State (all primitives or interned strings — no per-row allocation):**

- `out: java.io.Writer` — the sink.
- `delimiter: Int`, `quoteChar: Int`, `lineTerminator: String` — unpacked from `CsvConfig` in the constructor so each emit is a direct field read.
- `first: Boolean` — "is this the first field of the current row?".
- `scratch: Array[Char]` of length 20 — enough for `Long.MinValue` = `-9223372036854775808` (20 chars). Reused for every integer emit.

**Row lifecycle (package-private, called only by `CsvWriter`):**

- `beginRow(): Unit` — sets `first = true`.
- `endRow(): Unit` — writes `lineTerminator`.

**Per-field prelude.** Every public `emitX` starts with:

```scala
if (!first) out.write(delimiter) else first = false
```

Codecs therefore never think about delimiters. Each `emitX` call is a self-contained "one field".

**Integer emission (`emitInt` / `emitLong`).** Reverse-digit loop into `scratch`, then a single `out.write(scratch, offset, len)`. Special-case `Int.MinValue` / `Long.MinValue` because `-MIN_VALUE` overflows; after the sign branch, operate on a positive number.

**Short / Byte.** Delegate to `emitInt`.

**Boolean.** `out.write(if (b) "true" else "false")` — interned literals, no allocation.

**Char.** Treated as a 1-character field with full RFC-4180 escape treatment (if `c` is a delimiter, quote, CR, or LF, wrap in quotes; double the quote if necessary).

**Float / Double carve-out.** `out.write(java.lang.Float.toString(f))` / `Double.toString`. One `String` allocation per cell. Documented; optionally revisitable via Ryu-style if ever a hot spot.

**String emission (fast path).** Same two-pass strategy as the prior internal baseline:

1. Walk the string once to detect `needsQuoting` (delimiter, quote, CR, LF) and `hasQuote`.
2. If `!needsQuoting`: single `out.write(s)`. Zero allocations, one Writer call.
3. If `needsQuoting && !hasQuote`: `out.write(quoteChar)` + `out.write(s)` + `out.write(quoteChar)`. Zero allocations.
4. If `hasQuote`: `out.write(quoteChar)` + char-by-char loop doubling quotes + `out.write(quoteChar)`. Zero allocations; multiple Writer calls only on this rare path.

**`emitEmpty()`.** Just the delimiter prelude; no content. Used by `Option[None]` and any caller that wants an explicit empty cell.

**Concurrency.** Not thread-safe. One writer per thread. Matches `java.io.Writer` conventions and the prior internal baseline.

## 6. Codecs & derivation

### 6.1 `CsvFieldEncoder[-A]`

A SAM trait:

```scala
trait CsvFieldEncoder[-A]:
  def encode(a: A, out: FieldEmitter): Unit
```

All givens are singletons delegating to the matching `FieldEmitter` method:

```scala
given CsvFieldEncoder[Int]    = (a, out) => out.emitInt(a)
given CsvFieldEncoder[String] = (a, out) => out.emitString(a)
// ...
```

**One-alloc carve-out set** — these encoders call `out.emitString(v.toString)` (or an equivalent canonical `String` form), matching zio-blocks' behaviour:

- `BigInt`, `BigDecimal`
- `UUID` (`UUID.toString`)
- `Currency` (`Currency.getCurrencyCode`)
- All shipped `java.time.*` types (their `.toString` is the ISO-8601 canonical form)

**`Option[A]`:**

```scala
given [A](using inner: CsvFieldEncoder[A]): CsvFieldEncoder[Option[A]] =
  (a, out) => a match
    case Some(v) => inner.encode(v, out)
    case None    => out.emitEmpty()
```

### 6.2 `CsvRowEncoder[-A]`

```scala
trait CsvRowEncoder[-A]:
  def headerNames: IndexedSeq[String]
  def encode(a: A, out: FieldEmitter): Unit
```

`headerNames` is computed once at derivation time, stored in a `val`, and never recomputed.

### 6.3 Derivation — flat products

`inline def derived[A](using m: Mirror.ProductOf[A]): CsvRowEncoder[A]` uses `scala.compiletime` primitives:

1. `constValueTuple[m.MirroredElemLabels]` materialises the field labels as a compile-time `Tuple` of literal `String`s. Copied into an `Array[String]` once at derivation, wrapped as `ArraySeq` → the stored `headerNames`.
2. `summonAll[Tuple.Map[m.MirroredElemTypes, CsvFieldEncoder]]` materialises all field encoders as a tuple of given instances. Stored in an `Array[CsvFieldEncoder[Any]]` — one allocation at derivation time, reused for every row.
3. `encode(a, out)` casts `a` to `Product`, loops `0 until encoders.length`, and invokes `encoders(i).encode(a.productElement(i), out)`. Straight-line, monomorphic call sites → HotSpot inlines after warmup.

The `productElement` + cast path *does* box primitive fields (`Int`, `Long`, etc.) on each row — this is a Scala cost of `Product` access, not something csvzen adds, and it is the remaining non-zero-alloc step in the codec path for v1. Eliminating it requires per-field unrolled accessors (approach B — see §10).

**Compile-time diagnostics.** `summonAll` already produces a clean `"no given instance of type CsvFieldEncoder[Foo]"` message. We do not add custom error machinery.

**Nested case classes / sealed traits / tuples / sequences.** Not supported. A nested case-class field simply fails `summonAll` at compile time because we do not provide a `CsvFieldEncoder` from a `CsvRowEncoder` (by design — silently flattening would change column counts). Users wanting a specific behaviour can write a hand-rolled `CsvFieldEncoder[X]` for their type.

## 7. `CsvWriter` orchestration

```scala
final class CsvWriter private[csvzen] (out: Writer, config: CsvConfig) extends AutoCloseable:
  private val emitter = new FieldEmitter(out, config)

  inline def writeRow[A](a: A)(using enc: CsvRowEncoder[A]): Unit =
    emitter.beginRow()
    enc.encode(a, emitter)
    emitter.endRow()

  inline def writeRow(inline body: FieldEmitter => Unit): Unit =
    emitter.beginRow()
    body(emitter)
    emitter.endRow()

  inline def writeHeader[A]()(using enc: CsvRowEncoder[A]): Unit =
    writeHeader(enc.headerNames)

  def writeHeader(names: IndexedSeq[String]): Unit =
    emitter.beginRow()
    var i = 0
    while i < names.length do
      emitter.emitString(names(i))
      i += 1
    emitter.endRow()

  inline def writeAll[A](rows: Iterable[A])(using enc: CsvRowEncoder[A]): Unit =
    val it = rows.iterator
    while it.hasNext do writeRow(it.next())

  def close(): Unit = out.close()

object CsvWriter:
  def open(path: Path, config: CsvConfig): CsvWriter =
    new CsvWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8), config)
```

Notes:

- Only `CsvWriter.open(path, config)` is public. No `Writer`-accepting constructor; no `OutputStream + Charset`.
- The `inline def writeRow(inline body: FieldEmitter => Unit)` escape hatch preserves the baseline's closure-erasure property: the lambda is inlined at the call site, nothing is allocated per row.
- `writeAll` is `inline` and monomorphic in `A`; loop body is `writeRow(it.next())`, also inlined.

## 8. Error handling

- All `IOException`s from the underlying `Writer` propagate raw. No wrapping. No custom `CsvException`.
- `CsvWriter.open(path, config)` can throw `IOException` (from `Files.newBufferedWriter`). Caller manages resource lifecycle (`scala.util.Using`, `try/finally`, future `ZIO.fromAutoCloseable`).
- `close()` propagates `IOException` as usual.
- Derivation errors are compile-time only (missing `CsvFieldEncoder`, non-`Mirror.ProductOf`).
- No partial-row cleanup. If a codec throws mid-row, the underlying `Writer` has already received partial content; csvzen makes no all-or-nothing guarantee per row. Callers needing transactionality buffer themselves.

## 9. Testing

Framework: ZIO Test (`ZIOSpecDefault`), matching the broader workspace. Test naming convention: suite names use `::methodName` for methods, `.functionName` for functions; test descriptions in present tense without "should"/"must".

### 9.1 `FieldEmitterSpec` — every primitive, every edge

- **`emitInt`:** `0`, `1`, `-1`, `10`, `-10`, `9`, `-9`, `99`, `-99`, `100`, `-100`, `Int.MaxValue`, `Int.MinValue`, `Int.MaxValue - 1`, `Int.MinValue + 1`, plus a seeded 10 000-value randomised sweep asserted against `Integer.toString`.
- **`emitLong`:** same pattern as `emitInt`; additionally `Int.MaxValue + 1L`, `Int.MinValue - 1L`, `Long.MaxValue`, `Long.MinValue`.
- **`emitShort`:** exhaustive over `-32768..32767`.
- **`emitByte`:** exhaustive over `-128..127`.
- **`emitBoolean`:** `true` and `false`.
- **`emitFloat` / `emitDouble`:** `0.0`, `-0.0`, `+Inf`, `-Inf`, `NaN`, subnormals, `Float.MinValue`, `Float.MaxValue`, `Math.PI`, `Math.E`, money-shaped values, each asserted against `.toString`. Documents the carve-out.
- **`emitChar`:** all 7-bit ASCII; one BMP non-ASCII (`é`); a surrogate-pair case documented as requiring two calls.
- **`emitString`:** matrix over `{no-specials, contains-delimiter, contains-quote, contains-LF, contains-CR, contains-CRLF, contains-multiple-specials}` × `{default, TSV+hash-quote, LF-only terminator, semicolon+single-quote}`. Empty string. Very long (100 000 chars, no specials) to exercise the fast path. Unicode (CJK, emoji with surrogate pair). Special at position 0, middle, and last.
- **`emitEmpty`:** two in a row produce `","`; single on a row produces `""`.
- **Delimiter-placement matrix:** for every combination of two consecutive emit kinds (emitInt+emitString, emitEmpty+emitInt, emitChar+emitBoolean, …), exactly one delimiter between them and none before the first.

### 9.2 `CsvWriterSpec` — in-memory (`StringWriter`)

- Every baseline writeRow test, re-expressed on the new API (the escape-hatch `writeRow(body: FieldEmitter => Unit)` has an equivalent shape).
- Codec-driven `writeRow[A]` for case classes spanning every shipped primitive, `Option[Some]`, `Option[None]`, `Option[String]` with specials, mixed arities.
- `writeAll[A]`: order preservation across `Vector`, `List`, `Iterator`, `LazyList`; empty iterable; single element; large batch (size chosen to stay fast in CI).
- `writeHeader[A]()`: labels in declaration order for arities 1 / 2 / 5 / 22; labels containing specials (quoted in header).
- `writeHeader(names)`: unchanged baseline behaviour.
- `close()`: idempotent; underlying `Writer.close` called; post-close writes surface the Writer's error.
- Non-default configs (TSV, semicolon, LF-only, hash-quote) propagated through the full codec path.

### 9.3 `CsvFileWriterSpec` — real-file round-trips

This spec exercises the full I/O + charset path. Every test uses `CsvWriter.open(tmpPath, config)`, writes, closes, and reads the file back.

**Helper.** Creates a tmp file, runs a write body, closes the writer, returns `Files.readString(path, UTF_8)` and `Files.readAllBytes(path)`.

- **UTF-8 correctness.** Strings containing `é`, `ü`, CJK (`日本語`), emoji (`🎉` — surrogate pair), arrow (`→`); assert bytes against hand-computed UTF-8 sequences.
- **No BOM.** `Files.newBufferedWriter` + `UTF_8` does not write a BOM; regression guard asserts the first bytes of a non-empty file match the logical first character.
- **Line terminators.** Default `\r\n` produces `0x0D 0x0A`; LF-only config produces `0x0A`; no stray terminator beyond what `lineTerminator` emitted.
- **Empty file.** Open + close without writing → 0-byte file.
- **Header-only.** `writeHeader[A]()` + close → exactly the header line + one terminator.
- **Large file.** 100 000 rows of a mixed-type case class (primitives + `Option` + strings needing escape). Read back, split on `\r\n`, assert line count = 1 + 100 000 and first/middle/last row content.
- **Quoting round-trip.** Every entry in the `emitString` escape matrix, but hitting disk. Read back, parse with a tiny test-only state-machine CSV reader (in test helpers, sole purpose: verifying the bytes we wrote parse back to the values we asked to write).
- **File-overwrite semantics.** Opening on an existing file truncates; only new content remains.
- **Missing parent directory.** `CsvWriter.open` throws `IOException`; file not created.
- **Round-trip via every `CsvFieldEncoder`.** For each of the 28 shipped encoders: write a case class containing that field type, read the file back, parse the cell text with the canonical `String → A` parser (`Integer.parseInt`, `Instant.parse`, …), assert equal to the original. This is the "bytes mean what they claim" guarantee.

### 9.4 `CsvFieldEncoderSpec` — every shipped given

For each of the 28 shipped types, asserted via `FieldEmitter` + `StringWriter`:

- Nominal value.
- Boundary values where applicable (`Int.MaxValue`, `Instant.MIN` / `Instant.MAX`, `BigDecimal` with scale > 0, random and name-UUID, `Currency.getInstance("EUR"/"USD"/"JPY")`, every `DayOfWeek`, every `Month`).
- `Option[A]` × each: `None` emits empty; `Some(v)` matches the inner encoder's output.

### 9.5 `CsvRowEncoderSpec` — derivation

- `derives CsvRowEncoder` compiles and produces correct `headerNames` + `encode` for arities 1 / 2 / 5 / 10 / 22.
- Case class with every shipped field type present simultaneously.
- Label preservation including backticked identifiers (`` `user-id` ``) and unicode labels.
- Output byte-identical between the codec path and the equivalent hand-written escape-hatch `writeRow(lambda)`.
- **Negative compile-time tests** via `scala.compiletime.testing.typeCheckErrors` / `compileErrors`:
  - Nested case-class field → error identifies the missing `CsvFieldEncoder[X]`.
  - Non-product type (e.g. `List[Int]`) → derivation fails clearly.
  - Field of a user-defined type with no given encoder → error cites the offending field name and type.

### 9.6 Out of scope for v1 tests

- JMH micro-benchmarks.
- Concurrency / thread-safety tests (single-threaded contract documented).
- Property-based generators beyond the seeded RNG sweeps above.

## 10. Future considerations

- **Approach B: full-`inline` derivation.** Replace the `Array[CsvFieldEncoder[Any]]` + `productElement` machinery with a fully-unrolled inline macro that expands to `out.emitInt(a.age); out.emitString(a.name); …` at each call site. Eliminates the remaining `productElement` boxing of primitive fields and all typeclass dispatch, but costs compile time and debuggability. To be explored on an experimental branch and benchmarked against A.
- **`csvzen-zio` module.** `CsvWriter.managed(path, config): ZIO[Scope, Throwable, CsvWriter]` plus a `ZSink[Any, Throwable, A, Nothing, Long]` factory (given `CsvRowEncoder[A]`) returning row count.
- **Ryu-style `emitDouble`.** Eliminates the Double/Float carve-out if ever needed.
- **Custom header-naming.** `@csvName("x")` annotation read by the derivation macro. Non-breaking.
- **Readers.** A `CsvFieldDecoder` / `CsvRowDecoder` story mirroring the writer side. Independently shippable; no changes required to writer API.

## 11. References

- A prior internal CSV writer: the owns-the-Writer + inline-emit pattern and two-pass escape scan originate there.
- zio-blocks codec inspiration: [`zio-blocks/schema-csv`](https://github.com/zio/zio-blocks/tree/main/schema-csv) — we keep the primitive codec set and escape rules, and explicitly reject the per-cell `.toString` allocations.
- [RFC 4180](https://datatracker.ietf.org/doc/html/rfc4180).
