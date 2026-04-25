# csvzen

CSV made fast and simple.

A zero-allocation streaming CSV writer for **Scala 3**, built around a typed
field emitter and compile-time-derived row encoders. RFC 4180 compliant by
default, with configurable delimiter, quote character and line terminator.

## Modules

| Module            | Description                                                                 |
|-------------------|-----------------------------------------------------------------------------|
| `csvzen-core`     | Streaming `CsvWriter`, `CsvConfig`, derived row encoders.                   |
| `csvzen-test-kit` | Golden-file assertions for `CsvRowEncoder` output, on top of `zio-test`.    |

## Install

`csvzen` targets Scala 3.3+ (LTS).

```sbt
libraryDependencies += "com.guizmaii" %% "csvzen-core"     % "<latest-version>"
libraryDependencies += "com.guizmaii" %% "csvzen-test-kit" % "<latest-version>" % Test
```

### `-Xmax-inlines` for large case classes

`derives CsvRowEncoder` walks the tuple of field encoders inline-recursively,
one level per field. Scala 3's default `-Xmax-inlines:32` is enough up to
~25 fields; beyond that the derivation trips with *"Maximal number of
successive inlines (32) exceeded"*. Bump it in your `build.sbt`:

```sbt
scalacOptions ++= Seq("-Xmax-inlines:128")
```

128 covers the maximum useful arity (the JDK's case-class implementation
limit isn't far behind), and the cost is paid only at compile time, only
in files that derive a large encoder.

## Quick start

### Write a case class to a file

`derives CsvRowEncoder` gives you an encoder whose header names are the field
labels in declaration order. `writeHeader[A]()` writes those labels; `writeAll`
streams the rows.

```scala
import com.guizmaii.csvzen.core.*
import java.nio.file.Paths

final case class Person(name: String, age: Int, city: Option[String]) derives CsvRowEncoder

val people = List(
  Person("Ada",   36, Some("London")),
  Person("Linus", 55, None),
  Person("Grace", 85, Some("New York, NY")),
)

val path = Paths.get("people.csv")
val writer = CsvWriter.open(path, CsvConfig.default)
try {
  writer.writeHeader[Person]()
  writer.writeAll(people)
} finally writer.close()
```

Output (`\r\n` terminators by default):

```
name,age,city
Ada,36,London
Linus,55,
Grace,85,"New York, NY"
```

### Custom dialect

`CsvConfig` controls delimiter, quote character and line terminator. Validation
runs in the constructor — invalid combinations throw `IllegalArgumentException`.

```scala
val tsv = CsvConfig(delimiter = '\t', lineTerminator = "\n")
val writer = CsvWriter.open(Paths.get("data.tsv"), tsv)
```

Allowed line terminators: `"\n"`, `"\r"`, `"\r\n"`.

### Manual emission (escape hatch)

When you don't want a derived encoder, take the `FieldEmitter` directly. Each
`emit*` call writes one self-contained field; the writer handles delimiters and
the row terminator.

```scala
writer.writeRow { e =>
  e.emitString("hello")
  e.emitInt(42)
  e.emitDouble(3.14)
  e.emitEmpty()           // empty cell
}
// hello,42,3.14,
```

### Charset and open options

`CsvWriter.open` defaults to UTF-8 and the standard create/truncate behaviour.
Pass a different `Charset` or `OpenOption`s when you need to:

```scala
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption

val w = CsvWriter.open(
  path,
  CsvConfig.default,
  StandardCharsets.ISO_8859_1,
  StandardOpenOption.CREATE,
  StandardOpenOption.APPEND,
)
```

## Supported field types

Out of the box, `CsvFieldEncoder` is provided for:

- Primitives: `String`, `Int`, `Long`, `Short`, `Byte`, `Boolean`, `Char`,
  `Float`, `Double`
- Numeric: `BigInt`, `BigDecimal`
- `UUID`, `Currency`
- `java.time.*`: `Instant`, `LocalDate`, `LocalDateTime`, `LocalTime`,
  `OffsetDateTime`, `OffsetTime`, `ZonedDateTime`, `Duration`, `Period`,
  `Year`, `YearMonth`, `MonthDay`, `Month`, `DayOfWeek`, `ZoneId`, `ZoneOffset`
- `Option[A]` for any supported `A` — `None` becomes an empty cell.

### Custom encoders

A `CsvFieldEncoder[A]` is a single method `(A, FieldEmitter) => Unit`. The
emitter takes care of escaping; you choose the textual form.

```scala
import com.guizmaii.csvzen.core.*

enum Color { case Red, Green, Blue }
object Color {
  given CsvFieldEncoder[Color] = (c, out) => out.emitString(c.toString.toLowerCase)
}

final case class Pixel(x: Int, y: Int, color: Color) derives CsvRowEncoder
```

### Writing only some fields of a case class

`derives CsvRowEncoder` always emits every field. To project a subset (or
reorder columns, or rename headers), build the encoder explicitly with
`CsvRowEncoder.custom`:

```scala
import com.guizmaii.csvzen.core.*

final case class User(
  id: Long,
  email: String,
  passwordHash: String,   // sensitive — must not appear in the CSV
  name: String,
  createdAt: java.time.Instant,
)
object User {
  given CsvRowEncoder[User] =
    CsvRowEncoder.custom(IndexedSeq("id", "name", "email")) { (u, out) =>
      out.emitLong(u.id)
      out.emitString(u.name)
      out.emitString(u.email)
    }
}

// writeHeader[User]() and writeAll(users) now use this encoder and
// emit only id, name, email — passwordHash and createdAt stay out.
```

## Escaping

`csvzen` follows RFC 4180:

- Fields containing the delimiter, the quote character, `\r` or `\n` are
  enclosed in quotes.
- Embedded quote characters are doubled.
- Plain fields are written verbatim — no allocation, no copying.

```
he said "hi"   →   "he said ""hi"""
a,b            →   "a,b"
line1\nline2   →   "line1\nline2"
```

## Concurrency and error handling

- A `CsvWriter` (and its `FieldEmitter`) holds mutable per-row state and
  **must not** be shared across threads.
- After an `IOException` from the underlying `Writer`, the writer is unusable.
  csvzen does not attempt to recover from a partial row write — close the
  writer and discard the file.
- `flush()` flushes the underlying `Writer`; `close()` flushes and then
  closes it.

## Golden tests

### Why you want them

CSV output is a wire format. Once consumers exist — a downstream pipeline,
a partner who imports the file daily, an Excel sheet someone wired up two
years ago — your encoder's output is **a contract**. Any change in bytes
the encoder produces is a change in that contract: a column reordered, a
date format tweaked, a CRLF turned into LF, a `None` rendered as `""` instead
of `"null"`. Each is the kind of "harmless cleanup" that quietly breaks a
consumer in production three weeks later.

A golden test (a.k.a. snapshot test) is a small, opinionated answer to that
problem:

1. You commit a reference file — *the golden* — that captures what the
   encoder produces today for a representative set of inputs.
2. On every test run, the encoder is re-executed against the same inputs
   and the output is compared **byte-for-byte** to the golden.
3. If anything in the encoder's output changes — intended or not — the
   test fails and shows you the diff.

That's the whole mechanism. The value comes from what it forces:

- **No silent format changes.** Refactoring a `CsvFieldEncoder`, swapping a
  date library, "fixing" a quoting rule — all of it surfaces as a failing
  test with a visible diff, not as a wire-format regression that ships.
- **Cheap to write, dense in coverage.** One `csvGoldenTest(gen)` call
  exercises 50+ rows of randomised but stable input through the entire
  encoder stack. You don't write per-field assertions; you commit one file.
- **The diff is the spec.** When the change *is* intentional, you just open
  the `_changed.csv` next to the original, eyeball the diff to confirm the
  delta is what you wanted, and rename it over the original. The PR review
  then has a one-file diff that says exactly how the wire format moved.
- **Catches the boring stuff for free.** Line-terminator drift, accidental
  quoting of a previously-unquoted column, an extra trailing newline, an
  encoding library that started emitting `+00:00` instead of `Z` — all
  surface immediately, not at 3 AM in production.

The cost is one checked-in file per encoder shape and one rename when the
contract intentionally changes. Worth it.

### Usage

`csvzen-test-kit` ships golden-file assertions for `CsvRowEncoder` output,
modelled on `zio-json-golden`. Add it to the `Test` config and call
`csvGoldenTest(gen)` from a `ZIOSpecDefault` (the prefix avoids a name clash
with `zio-json-golden`'s own `goldenTest` if both are on the classpath):

```scala
import com.guizmaii.csvzen.core.*
import com.guizmaii.csvzen.testkit.*
import zio.test.*
import zio.test.magnolia.DeriveGen

object UserSpec extends ZIOSpecDefault {
  final case class User(id: Int, name: String, active: Boolean) derives CsvRowEncoder

  override def spec = suite("UserSpec")(
    csvGoldenTest(DeriveGen[User])
  )
}
```

> **Tip on `String` fields.** `DeriveGen[String]` reaches for the full Unicode
> `Char` range — RTL Arabic, surrogate pairs, control chars. The bytes are
> correct, but Unicode bidi can visually scramble cells in IDEs. For
> `String`-heavy records, hand-roll the generator with `Gen.alphaNumericString`
> instead. `DeriveGen` also only ships instances for primitives + a handful of
> stdlib types — for `BigInt` / `BigDecimal` / `UUID` / `Currency` / `java.time.*`
> you need to provide a `Gen` by hand regardless. See `test-kit/README.md` and
> `GoldenSpec` for the full pattern.

First run writes `src/test/resources/golden/User_new.csv` and fails. Drop the
`_new` suffix to accept the snapshot. Subsequent runs compare against the
on-disk file; on mismatch a `_changed.csv` is written next to the original so
you can diff. Promotion is always an explicit file rename — no env-var
auto-update mode.

See [`test-kit/README.md`](test-kit/README.md) for `GoldenConfiguration`
options (custom `CsvConfig`, sample size, `relativePath`) and the full
workflow.

## Build

```bash
sbt --client tc        # Test/compile
sbt --client test      # Run the test suite (ZIO Test)
```

## License

[Apache 2.0](LICENSE)
