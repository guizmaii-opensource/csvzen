# csvzen

CSV made fast and simple.

A zero-allocation streaming CSV writer for **Scala 3**, built around a typed
field emitter and compile-time-derived row encoders. RFC 4180 compliant by
default, with configurable delimiter, quote character and line terminator.

## Modules

| Module        | Description                                               |
|---------------|-----------------------------------------------------------|
| `csvzen-core` | Streaming `CsvWriter`, `CsvConfig`, derived row encoders. |

## Install

`csvzen` targets Scala 3.3+ (LTS).

```sbt
libraryDependencies += "com.guizmaii" %% "csvzen-core" % "<latest-version>"
```

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

## Build

```bash
sbt --client tc        # Test/compile
sbt --client test      # Run the test suite (ZIO Test)
```

## License

[Apache 2.0](LICENSE)
