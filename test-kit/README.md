# csvzen-test-kit

Golden-test capabilities for csvzen, modelled on `zio-json-golden`.

## What is golden testing?

A golden file (a.k.a. snapshot) is a checked-in reference of the expected output
of your encoder. Each test run regenerates the output and compares it to the
checked-in file; mismatches fail the test and write a `_changed.csv` next to the
golden so you can diff.

When the encoder's behaviour changes — intentionally — you promote the
`_changed.csv` over the original. When it's a regression, the test caught it.

## Usage

```scala
libraryDependencies += "com.guizmaii" %% "csvzen-test-kit" % "<latest-version>" % Test
```

```scala
import com.guizmaii.csvzen.core.*
import com.guizmaii.csvzen.testkit.*
import zio.test.*
import zio.test.magnolia.DeriveGen

object MyEncoderSpec extends ZIOSpecDefault {

  final case class User(id: Int, name: String, active: Boolean) derives CsvRowEncoder

  override def spec = suite("MyEncoderSpec")(
    csvGoldenTest(DeriveGen[User])
  )
}
```

## Workflow

1. **First run** with no golden file → writes
   `src/test/resources/golden/User_new.csv` and fails the test with
   *"No existing golden test for ... Remove `_new` from the suffix and re-run
   the test."* Drop the `_new` to accept the snapshot.
2. **Subsequent runs** → encoder output is compared to the on-disk file. On
   mismatch a `_changed.csv` is written next to the original and the test
   fails. If the change is intentional, overwrite the original; if not, the
   test caught a regression.

Promotion is always an explicit file rename — there's no env var or system
property that auto-updates goldens.

## Configuration

```scala
import com.guizmaii.csvzen.testkit.*

given GoldenConfiguration = GoldenConfiguration(
  relativePath = "users",   // file lives at src/test/resources/golden/users/User.csv
  sampleSize   = 50,        // 50 rows in the golden instead of the default 20
  csvConfig    = CsvConfig(delimiter = '\t', lineTerminator = "\n"),
)
```

`relativePath` is mainly useful when two specs derive a generator for two
different types that happen to share a short name (e.g. `User` from two
packages); set it on each so the golden files don't collide.

## Inspiration

This module mirrors `zio.json.golden`'s API and workflow as closely as the
target format allows. Credit to the zio-json contributors for the design.
