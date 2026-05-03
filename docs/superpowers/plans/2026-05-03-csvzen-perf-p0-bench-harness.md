# csvzen perf overhaul — Phase 0: Bench harness baseline

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `csvzen-bench` module with JMH benchmarks covering 4 schemas × 3 sizes × 2 sinks (24 cells) plus a buffer-size sweep, run them against the *current* `Writer`-backed implementation, and commit the resulting baseline as `modules/bench/results/p0-baseline/`. No csvzen runtime code is changed except a single stub method (`flushCount`) on `FieldEmitter`.

**Architecture:** New private (publish/skip) sbt module `bench` depending on `core`. Uses `sbt-jmh` for harness, JMH `@Benchmark` methods that drive `CsvWriter`, schemas defined as Scala 3 case classes with `derives CsvRowEncoder`. Benchmarks run on JDK 25; the rest of the build stays on JDK 17 for now.

**Tech stack:** sbt 1.12.10, Scala 3.3.7, sbt-jmh 0.4.7, JMH 1.37 (pulled by sbt-jmh), zio-test-magnolia 2.1.25 (for `DeriveGen`-based row generation, already on classpath via test-kit).

**Reference spec:** `docs/superpowers/specs/2026-05-03-csvzen-perf-design.md`

**Phase scope reminder.** This plan covers **P0 only**. P1 (byte[] rewrite), P2 (Schubfach), P3 (SWAR + Vector API), P4 (competitor benchmarks), P5 (release prep) each get their own plan, generated after the prior phase lands and its bench results are captured. Writing them now would force invented numbers (P1's chosen buffer-size default, P3's SWAR/Vector thresholds) the engineer cannot yet measure.

---

## File map

**New files (this phase creates):**

```
modules/bench/
  src/main/scala/com/guizmaii/csvzen/bench/
    Schemas.scala
    BenchData.scala
    NullOutputStream.scala
    CsvWriteBench.scala
    BufferSweepBench.scala
  src/test/scala/com/guizmaii/csvzen/bench/
    NullOutputStreamSpec.scala
    BenchDataSpec.scala
  README.md
  results/
    p0-baseline/
      csv-write.json            (JMH output — generated, committed)
      buffer-sweep.json         (JMH output — generated, committed)
      SUMMARY.md                (human-written summary of the JSON)
```

**Modified files:**

```
build.sbt                            (add bench module + aggregate)
project/plugins.sbt                  (add sbt-jmh)
modules/core/src/main/scala/com/guizmaii/csvzen/core/FieldEmitter.scala
                                     (add flushCount stub)
modules/core/src/test/scala/com/guizmaii/csvzen/core/FieldEmitterSpec.scala
                                     (add flushCount surface test)
```

---

## Task 1: Add sbt-jmh plugin

**Files:**
- Modify: `project/plugins.sbt`

- [ ] **Step 1: Add the plugin entry**

Open `project/plugins.sbt` and append the line:

```sbt
addSbtPlugin("pl.project13.scala" % "sbt-jmh"        % "0.4.7")
```

Final file contents:

```sbt
addSbtPlugin("com.timushev.sbt" % "sbt-updates"    % "0.6.4")
addSbtPlugin("ch.epfl.scala"    % "sbt-scalafix"   % "0.14.6")
addSbtPlugin("org.scalameta"    % "sbt-scalafmt"   % "2.6.0")
addSbtPlugin("org.typelevel"    % "sbt-tpolecat"   % "0.5.3")
addSbtPlugin("com.github.sbt"   % "sbt-ci-release" % "1.11.2")
addSbtPlugin("pl.project13.scala" % "sbt-jmh"        % "0.4.7")
```

- [ ] **Step 2: Reload sbt and verify the plugin is available**

Run:

```bash
sbt --client reload
```

Expected: command completes without error. The plugin is now resolvable; the `Jmh` task scope will exist on any project that enables `JmhPlugin`.

- [ ] **Step 3: Commit**

```bash
git add project/plugins.sbt
git commit -m "Add sbt-jmh plugin"
```

---

## Task 2: Create the empty bench module skeleton

**Files:**
- Create directory: `modules/bench/src/main/scala/com/guizmaii/csvzen/bench/`
- Create directory: `modules/bench/src/test/scala/com/guizmaii/csvzen/bench/`
- Create directory: `modules/bench/results/p0-baseline/`
- Modify: `build.sbt`

- [ ] **Step 1: Create the directory tree**

```bash
mkdir -p modules/bench/src/main/scala/com/guizmaii/csvzen/bench
mkdir -p modules/bench/src/test/scala/com/guizmaii/csvzen/bench
mkdir -p modules/bench/results/p0-baseline
```

- [ ] **Step 2: Add `bench` to `build.sbt`**

Add this `lazy val` after the `zio` definition in `build.sbt`:

```scala
lazy val bench =
  project
    .in(file("modules/bench"))
    .dependsOn(core)
    .enablePlugins(JmhPlugin)
    .settings(stdSettings *)
    .settings(
      name           := "csvzen-bench",
      publish / skip := true,
      // Bench JVM is JDK 25 (per design doc Q5). The library itself stays on
      // javaTarget 17 until the JDK floor is bumped in P3/P5; the bench module
      // overrides via -release so it can use newer JDK features in the harness.
      javacOptions   := Seq("-source", "21", "-target", "21"),
      scalacOptions  := scalacOptions.value.filterNot(_.startsWith("-release")) :+ "-release:21",
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio-test"          % zioVersion % Test,
        "dev.zio" %% "zio-test-sbt"      % zioVersion % Test,
        "dev.zio" %% "zio-test-magnolia" % zioVersion,
      ),
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    )
```

And update the root project's `aggregate` from:

```scala
.aggregate(core, `test-kit`, zio)
```

to:

```scala
.aggregate(core, `test-kit`, zio, bench)
```

- [ ] **Step 3: Verify the bench module compiles**

Run:

```bash
sbt --client "bench/compile"
```

Expected: `[success]`. With no source files yet, sbt-jmh's `JmhPlugin` should still configure the project cleanly.

- [ ] **Step 4: Commit**

```bash
git add build.sbt modules/bench
git commit -m "Add empty csvzen-bench module"
```

---

## Task 3: Add `flushCount` stub on `FieldEmitter` (TDD)

The bench harness will eventually assert on `flushCount` to catch syscall regressions. P0 only adds the *surface*; the value is always `0` against the current `Writer`-backed code. P1's rewrite swaps the implementation to a real counter incremented on every byte-buffer flush, and the test in this task gets meaningful assertions in P1.

**Files:**
- Modify: `modules/core/src/main/scala/com/guizmaii/csvzen/core/FieldEmitter.scala`
- Modify: `modules/core/src/test/scala/com/guizmaii/csvzen/core/FieldEmitterSpec.scala`

- [ ] **Step 1: Write the failing test**

Open `modules/core/src/test/scala/com/guizmaii/csvzen/core/FieldEmitterSpec.scala`. Add this test inside the existing `suite("FieldEmitter")` block:

```scala
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
```

- [ ] **Step 2: Run the test, verify it fails to compile**

Run:

```bash
sbt --client "core/Test/compile"
```

Expected: compile error — `value flushCount is not a member of com.guizmaii.csvzen.core.FieldEmitter`.

- [ ] **Step 3: Add the stub method on `FieldEmitter`**

In `modules/core/src/main/scala/com/guizmaii/csvzen/core/FieldEmitter.scala`, add this method just below the existing `private[core] def endRow()` (around line 27):

```scala
/**
 * Number of times the buffer has been flushed to the underlying sink. P0 stub
 * (always returns `0` on the `Writer`-backed implementation); P1 swaps in a
 * real counter incremented on every byte-buffer flush, used by the bench
 * module and by syscall-regression assertions.
 */
private[csvzen] def flushCount: Long = 0L
```

- [ ] **Step 4: Run the test, verify it passes**

Run:

```bash
sbt --client "core/testOnly *FieldEmitterSpec"
```

Expected: all tests pass, including the new `flushCount is reachable and starts at 0`.

- [ ] **Step 5: Commit**

```bash
git add modules/core/src/main/scala/com/guizmaii/csvzen/core/FieldEmitter.scala \
        modules/core/src/test/scala/com/guizmaii/csvzen/core/FieldEmitterSpec.scala
git commit -m "Add flushCount stub on FieldEmitter for bench instrumentation"
```

---

## Task 4: Define benchmark schemas

The four schemas isolate what each later optimization actually moves. `Mixed` reflects realistic CSV; the heavy variants pin down whether a phase helped where it should and didn't regress where it shouldn't.

**Files:**
- Create: `modules/bench/src/main/scala/com/guizmaii/csvzen/bench/Schemas.scala`

- [ ] **Step 1: Write `Schemas.scala`**

```scala
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
```

- [ ] **Step 2: Verify it compiles**

Run:

```bash
sbt --client "bench/compile"
```

Expected: `[success]`.

- [ ] **Step 3: Commit**

```bash
git add modules/bench/src/main/scala/com/guizmaii/csvzen/bench/Schemas.scala
git commit -m "Add bench schemas: Mixed, IntHeavy, DoubleHeavy, StringHeavy"
```

---

## Task 5: Define `BenchData` (precomputed row vectors)

JMH allocates the input data once per `@State`. Generating 10M rows on every `@Benchmark` invocation would dominate the measurement. We precompute everything in `@Setup(Trial)` from a fixed seed so every fork sees the same data.

**Files:**
- Create: `modules/bench/src/main/scala/com/guizmaii/csvzen/bench/BenchData.scala`
- Create: `modules/bench/src/test/scala/com/guizmaii/csvzen/bench/BenchDataSpec.scala`

- [ ] **Step 1: Write the failing test first**

Create `modules/bench/src/test/scala/com/guizmaii/csvzen/bench/BenchDataSpec.scala`:

```scala
package com.guizmaii.csvzen.bench

import zio.test.*

object BenchDataSpec extends ZIOSpecDefault {
  override def spec = suite("BenchData")(
    test("mixed(n) returns exactly n rows") {
      assertTrue(BenchData.mixed(1000).size == 1000)
    },
    test("intHeavy(n) returns exactly n rows") {
      assertTrue(BenchData.intHeavy(1000).size == 1000)
    },
    test("doubleHeavy(n) returns exactly n rows") {
      assertTrue(BenchData.doubleHeavy(1000).size == 1000)
    },
    test("stringHeavy(n) returns exactly n rows") {
      assertTrue(BenchData.stringHeavy(1000).size == 1000)
    },
    test("stringHeavy: roughly half the rows contain a comma") {
      val rows  = BenchData.stringHeavy(1000)
      val withComma =
        rows.count(r => r.a.contains(',') || r.b.contains(',') || r.c.contains(',') || r.d.contains(',') || r.e.contains(','))
      assertTrue(withComma >= 400 && withComma <= 600)
    },
    test("two calls with the same n return identical data (fixed seed)") {
      assertTrue(BenchData.mixed(100) == BenchData.mixed(100))
    },
  )
}
```

- [ ] **Step 2: Run the test, verify it fails to compile**

Run:

```bash
sbt --client "bench/Test/compile"
```

Expected: compile error — `not found: object BenchData`.

- [ ] **Step 3: Implement `BenchData.scala`**

Create `modules/bench/src/main/scala/com/guizmaii/csvzen/bench/BenchData.scala`:

```scala
package com.guizmaii.csvzen.bench

import com.guizmaii.csvzen.bench.Schemas.*

import java.time.Instant
import scala.util.Random

/**
 * Precomputed row vectors for benchmarks. All generators take a fixed seed so
 * every fork, every iteration, every JDK sees the same bytes — this is what
 * lets us compare phase-to-phase deltas without per-run noise.
 */
object BenchData {

  private val SeedMixed       = 1L
  private val SeedIntHeavy    = 2L
  private val SeedDoubleHeavy = 3L
  private val SeedStringHeavy = 4L

  def mixed(n: Int): Vector[Mixed] = {
    val r = new Random(SeedMixed)
    Vector.tabulate(n) { i =>
      Mixed(
        id     = r.nextLong(),
        name   = randAscii(r, 8 + r.nextInt(16)),
        count  = r.nextInt(),
        amount = r.nextDouble() * 10000.0,
        ts     = Instant.ofEpochMilli(1_700_000_000_000L + i.toLong * 1000L),
      )
    }
  }

  def intHeavy(n: Int): Vector[IntHeavy] = {
    val r = new Random(SeedIntHeavy)
    Vector.tabulate(n)(_ => IntHeavy(r.nextLong(), r.nextLong(), r.nextLong(), r.nextLong(), r.nextLong()))
  }

  def doubleHeavy(n: Int): Vector[DoubleHeavy] = {
    val r = new Random(SeedDoubleHeavy)
    Vector.tabulate(n) { _ =>
      DoubleHeavy(
        r.nextGaussian() * 1000.0,
        r.nextGaussian() * 1000.0,
        r.nextGaussian() * 1000.0,
        r.nextGaussian() * 1000.0,
        r.nextGaussian() * 1000.0,
      )
    }
  }

  def stringHeavy(n: Int): Vector[StringHeavy] = {
    val r = new Random(SeedStringHeavy)
    Vector.tabulate(n) { i =>
      // Every other row gets a comma in some field. This guarantees we hit
      // both the fast-path (no quoting) and the quoting path in equal measure.
      val needsQuoting = (i % 2) == 0
      val len          = 8 + r.nextInt(24)
      def field(): String = {
        val s = randAscii(r, len)
        if (needsQuoting) s.updated(len / 2, ',') else s
      }
      StringHeavy(field(), field(), field(), field(), field())
    }
  }

  private val AsciiAlphaNum: Array[Char] = (('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9')).toArray

  private def randAscii(r: Random, len: Int): String = {
    val a = new Array[Char](len)
    var i = 0
    while (i < len) { a(i) = AsciiAlphaNum(r.nextInt(AsciiAlphaNum.length)); i += 1 }
    new String(a)
  }
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run:

```bash
sbt --client "bench/test"
```

Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add modules/bench/src/main/scala/com/guizmaii/csvzen/bench/BenchData.scala \
        modules/bench/src/test/scala/com/guizmaii/csvzen/bench/BenchDataSpec.scala
git commit -m "Add BenchData with deterministic seeded generators per schema"
```

---

## Task 6: Implement `NullOutputStream` (TDD)

A pos-tracking `OutputStream` whose `write` methods do nothing except increment a counter. The null-sink benchmarks use it to isolate encoder cost from any IO path (and from page-cache effects on file benchmarks).

**Files:**
- Create: `modules/bench/src/main/scala/com/guizmaii/csvzen/bench/NullOutputStream.scala`
- Create: `modules/bench/src/test/scala/com/guizmaii/csvzen/bench/NullOutputStreamSpec.scala`

- [ ] **Step 1: Write the failing test**

Create `modules/bench/src/test/scala/com/guizmaii/csvzen/bench/NullOutputStreamSpec.scala`:

```scala
package com.guizmaii.csvzen.bench

import zio.test.*

object NullOutputStreamSpec extends ZIOSpecDefault {
  override def spec = suite("NullOutputStream")(
    test("counts single-byte writes") {
      val s = new NullOutputStream
      s.write(1); s.write(2); s.write(3)
      assertTrue(s.bytesWritten == 3L)
    },
    test("counts array writes") {
      val s = new NullOutputStream
      s.write(Array[Byte](1, 2, 3, 4))
      assertTrue(s.bytesWritten == 4L)
    },
    test("counts array slice writes") {
      val s = new NullOutputStream
      s.write(Array[Byte](1, 2, 3, 4, 5), 1, 3)
      assertTrue(s.bytesWritten == 3L)
    },
    test("reset() zeroes the counter") {
      val s = new NullOutputStream
      s.write(Array[Byte](1, 2, 3))
      s.reset()
      assertTrue(s.bytesWritten == 0L)
    },
  )
}
```

- [ ] **Step 2: Run, verify it fails**

Run:

```bash
sbt --client "bench/Test/compile"
```

Expected: compile error — `not found: type NullOutputStream`.

- [ ] **Step 3: Implement `NullOutputStream.scala`**

Create `modules/bench/src/main/scala/com/guizmaii/csvzen/bench/NullOutputStream.scala`:

```scala
package com.guizmaii.csvzen.bench

import java.io.OutputStream

/**
 * `OutputStream` that discards all bytes and only tracks how many were
 * written. Used by null-sink benchmarks to isolate encoder cost from disk
 * IO. Single-threaded — JMH benchmarks are single-threaded by default.
 */
final class NullOutputStream extends OutputStream {

  private var count: Long = 0L

  override def write(b: Int): Unit = { count += 1L }

  override def write(b: Array[Byte]): Unit = { count += b.length.toLong }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = { count += len.toLong }

  def bytesWritten: Long = count

  def reset(): Unit = { count = 0L }
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run:

```bash
sbt --client "bench/test"
```

Expected: all `NullOutputStreamSpec` tests pass alongside the existing `BenchDataSpec` tests.

- [ ] **Step 5: Commit**

```bash
git add modules/bench/src/main/scala/com/guizmaii/csvzen/bench/NullOutputStream.scala \
        modules/bench/src/test/scala/com/guizmaii/csvzen/bench/NullOutputStreamSpec.scala
git commit -m "Add NullOutputStream sink for byte-counting benchmarks"
```

---

## Task 7: Implement the main 24-cell benchmark (`CsvWriteBench`)

JMH benchmark methods, one per (schema × size × sink) combination. We use explicit `@Benchmark` methods rather than `@Param`-driven schema/size dispatch because schema types differ (`Mixed` vs. `IntHeavy` etc.) — `@Param` only dispatches on values, not types.

**Files:**
- Create: `modules/bench/src/main/scala/com/guizmaii/csvzen/bench/CsvWriteBench.scala`

- [ ] **Step 1: Write the benchmark class**

Create `modules/bench/src/main/scala/com/guizmaii/csvzen/bench/CsvWriteBench.scala`:

```scala
package com.guizmaii.csvzen.bench

import com.guizmaii.csvzen.bench.Schemas.*
import com.guizmaii.csvzen.core.{CsvConfig, CsvWriter}

import org.openjdk.jmh.annotations.*

import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.TimeUnit

/**
 * The 24-cell main bench: 4 schemas × 3 sizes × 2 sinks.
 *
 * Mode strategy:
 * - small (1k) and medium (100k): `Throughput` — ops/sec, where one op writes
 *   the full N-row batch.
 * - large (10M): `SingleShotTime` — Throughput at multi-second per-op produces
 *   noisy numbers because JMH's iteration scheduling assumes sub-second ops.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(
  value   = 2,
  jvmArgs = Array(
    "-Xms2g",
    "-Xmx2g",
    "-XX:+UseG1GC",
    // Harmless on P0 (we don't load the module). Required from P3 onward
    // when the Vector API quoting-scan path is added; setting it now keeps
    // the JVM args stable across phases.
    "--add-modules",
    "jdk.incubator.vector",
  ),
)
class CsvWriteBenchSmallMedium {

  // ---- Data -----------------------------------------------------------------

  var mixedSmall:        Vector[Mixed]       = _
  var mixedMedium:       Vector[Mixed]       = _
  var intHeavySmall:     Vector[IntHeavy]    = _
  var intHeavyMedium:    Vector[IntHeavy]    = _
  var doubleHeavySmall:  Vector[DoubleHeavy] = _
  var doubleHeavyMedium: Vector[DoubleHeavy] = _
  var stringHeavySmall:  Vector[StringHeavy] = _
  var stringHeavyMedium: Vector[StringHeavy] = _

  var tmpFile: Path = _

  @Setup(Level.Trial)
  def setupTrial(): Unit = {
    mixedSmall        = BenchData.mixed(1_000)
    mixedMedium       = BenchData.mixed(100_000)
    intHeavySmall     = BenchData.intHeavy(1_000)
    intHeavyMedium    = BenchData.intHeavy(100_000)
    doubleHeavySmall  = BenchData.doubleHeavy(1_000)
    doubleHeavyMedium = BenchData.doubleHeavy(100_000)
    stringHeavySmall  = BenchData.stringHeavy(1_000)
    stringHeavyMedium = BenchData.stringHeavy(100_000)
  }

  @Setup(Level.Iteration)
  def setupIteration(): Unit = {
    tmpFile = Files.createTempFile("csvzen-bench-", ".csv")
  }

  @TearDown(Level.Iteration)
  def teardownIteration(): Unit = {
    Files.deleteIfExists(tmpFile)
  }

  // ---- Helpers -------------------------------------------------------------

  /**
   * Writes header + all rows to a `NullOutputStream` wrapped in an
   * `OutputStreamWriter`/`BufferedWriter` chain that mirrors what
   * `CsvWriter.open` does on a real file. This is the *current*
   * `Writer`-backed shape — P1 will replace `unsafeFromWriter` with
   * `unsafeFromOutputStream` and this helper goes away.
   */
  private inline def writeAllNullSink[A](
    rows: Vector[A]
  )(using enc: com.guizmaii.csvzen.core.CsvRowEncoder[A]): Unit = {
    val sink   = new NullOutputStream
    val osw    = new OutputStreamWriter(sink, StandardCharsets.UTF_8)
    val bw     = new java.io.BufferedWriter(osw)
    val writer = CsvWriter.unsafeFromWriter(bw, CsvConfig.default)
    try {
      writer.writeHeader[A]()
      writer.writeAll(rows)
    } finally writer.close()
  }

  private inline def writeAllFile[A](
    rows: Vector[A]
  )(using enc: com.guizmaii.csvzen.core.CsvRowEncoder[A]): Unit = {
    val writer = CsvWriter.open(
      tmpFile,
      CsvConfig.default,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE,
    )
    try {
      writer.writeHeader[A]()
      writer.writeAll(rows)
    } finally writer.close()
  }

  // ---- Benchmarks: null sink, small ----------------------------------------

  @Benchmark def mixed_small_null:        Unit = writeAllNullSink(mixedSmall)
  @Benchmark def intHeavy_small_null:     Unit = writeAllNullSink(intHeavySmall)
  @Benchmark def doubleHeavy_small_null:  Unit = writeAllNullSink(doubleHeavySmall)
  @Benchmark def stringHeavy_small_null:  Unit = writeAllNullSink(stringHeavySmall)

  // ---- Benchmarks: null sink, medium ---------------------------------------

  @Benchmark def mixed_medium_null:       Unit = writeAllNullSink(mixedMedium)
  @Benchmark def intHeavy_medium_null:    Unit = writeAllNullSink(intHeavyMedium)
  @Benchmark def doubleHeavy_medium_null: Unit = writeAllNullSink(doubleHeavyMedium)
  @Benchmark def stringHeavy_medium_null: Unit = writeAllNullSink(stringHeavyMedium)

  // ---- Benchmarks: tmp file, small ----------------------------------------

  @Benchmark def mixed_small_file:        Unit = writeAllFile(mixedSmall)
  @Benchmark def intHeavy_small_file:     Unit = writeAllFile(intHeavySmall)
  @Benchmark def doubleHeavy_small_file:  Unit = writeAllFile(doubleHeavySmall)
  @Benchmark def stringHeavy_small_file:  Unit = writeAllFile(stringHeavySmall)

  // ---- Benchmarks: tmp file, medium ---------------------------------------

  @Benchmark def mixed_medium_file:       Unit = writeAllFile(mixedMedium)
  @Benchmark def intHeavy_medium_file:    Unit = writeAllFile(intHeavyMedium)
  @Benchmark def doubleHeavy_medium_file: Unit = writeAllFile(doubleHeavyMedium)
  @Benchmark def stringHeavy_medium_file: Unit = writeAllFile(stringHeavyMedium)
}

/**
 * Large-size (10M rows) benchmarks run in `SingleShotTime` mode because
 * Throughput's per-iteration scheduling becomes noisy when one op takes
 * multiple seconds. Forks/iterations are reduced accordingly.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SingleShotTime))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(
  value   = 1,
  jvmArgs = Array(
    "-Xms4g",
    "-Xmx4g",
    "-XX:+UseG1GC",
    "--add-modules",
    "jdk.incubator.vector",
  ),
)
class CsvWriteBenchLarge {

  var mixedLarge:       Vector[Mixed]       = _
  var intHeavyLarge:    Vector[IntHeavy]    = _
  var doubleHeavyLarge: Vector[DoubleHeavy] = _
  var stringHeavyLarge: Vector[StringHeavy] = _

  var tmpFile: Path = _

  @Setup(Level.Trial)
  def setupTrial(): Unit = {
    mixedLarge       = BenchData.mixed(10_000_000)
    intHeavyLarge    = BenchData.intHeavy(10_000_000)
    doubleHeavyLarge = BenchData.doubleHeavy(10_000_000)
    stringHeavyLarge = BenchData.stringHeavy(10_000_000)
  }

  @Setup(Level.Invocation)
  def setupInvocation(): Unit = {
    tmpFile = Files.createTempFile("csvzen-bench-", ".csv")
  }

  @TearDown(Level.Invocation)
  def teardownInvocation(): Unit = {
    Files.deleteIfExists(tmpFile)
  }

  private inline def writeAllNullSink[A](
    rows: Vector[A]
  )(using enc: com.guizmaii.csvzen.core.CsvRowEncoder[A]): Unit = {
    val sink   = new NullOutputStream
    val osw    = new OutputStreamWriter(sink, StandardCharsets.UTF_8)
    val bw     = new java.io.BufferedWriter(osw)
    val writer = CsvWriter.unsafeFromWriter(bw, CsvConfig.default)
    try {
      writer.writeHeader[A]()
      writer.writeAll(rows)
    } finally writer.close()
  }

  private inline def writeAllFile[A](
    rows: Vector[A]
  )(using enc: com.guizmaii.csvzen.core.CsvRowEncoder[A]): Unit = {
    val writer = CsvWriter.open(
      tmpFile,
      CsvConfig.default,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE,
    )
    try {
      writer.writeHeader[A]()
      writer.writeAll(rows)
    } finally writer.close()
  }

  @Benchmark def mixed_large_null:        Unit = writeAllNullSink(mixedLarge)
  @Benchmark def intHeavy_large_null:     Unit = writeAllNullSink(intHeavyLarge)
  @Benchmark def doubleHeavy_large_null:  Unit = writeAllNullSink(doubleHeavyLarge)
  @Benchmark def stringHeavy_large_null:  Unit = writeAllNullSink(stringHeavyLarge)

  @Benchmark def mixed_large_file:        Unit = writeAllFile(mixedLarge)
  @Benchmark def intHeavy_large_file:     Unit = writeAllFile(intHeavyLarge)
  @Benchmark def doubleHeavy_large_file:  Unit = writeAllFile(doubleHeavyLarge)
  @Benchmark def stringHeavy_large_file:  Unit = writeAllFile(stringHeavyLarge)
}
```

- [ ] **Step 2: Verify the bench compiles**

Run:

```bash
sbt --client "bench/Jmh/compile"
```

Expected: `[success]`. JMH's annotation processor generates wrapper classes; `Jmh/compile` exercises that path.

- [ ] **Step 3: Smoke-test with a single fast bench**

Run:

```bash
sbt --client "bench/Jmh/run -wi 1 -i 1 -f 1 -t 1 .*intHeavy_small_null.*"
```

Expected: one `intHeavy_small_null` benchmark runs, reports a number, exits cleanly. Do NOT trust the value — `-wi 1 -i 1 -f 1` gives noisy output. We just want to confirm the harness wires up.

- [ ] **Step 4: Commit**

```bash
git add modules/bench/src/main/scala/com/guizmaii/csvzen/bench/CsvWriteBench.scala
git commit -m "Add 24-cell main benchmark (CsvWriteBench)"
```

---

## Task 8: Implement `BufferSweepBench`

P0's sweep cannot use a `CsvWriter.open` buffer-size knob because none exists yet. Instead, the bench constructs the `BufferedWriter`/`OutputStreamWriter`/`OutputStream` chain manually with explicit buffer sizes via `BufferedWriter(writer, sz)`. This captures how the *current* Writer-based encoder behaves under different `BufferedWriter` sizes, which is the relevant baseline.

**Files:**
- Create: `modules/bench/src/main/scala/com/guizmaii/csvzen/bench/BufferSweepBench.scala`

- [ ] **Step 1: Write `BufferSweepBench.scala`**

Create `modules/bench/src/main/scala/com/guizmaii/csvzen/bench/BufferSweepBench.scala`:

```scala
package com.guizmaii.csvzen.bench

import com.guizmaii.csvzen.bench.Schemas.Mixed
import com.guizmaii.csvzen.core.{CsvConfig, CsvWriter}

import org.openjdk.jmh.annotations.*

import java.io.{BufferedWriter, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.TimeUnit

/**
 * Sweeps the `BufferedWriter` buffer size against `Mixed @ 100k` to capture
 * the throughput-vs-buffer-size curve on the current Writer-backed encoder.
 * The optimum we read off here is mostly informational — the real default is
 * picked at P1 against the byte[] rewrite, where the buffer ownership shape
 * is different.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(
  value   = 2,
  jvmArgs = Array(
    "-Xms2g",
    "-Xmx2g",
    "-XX:+UseG1GC",
    "--add-modules",
    "jdk.incubator.vector",
  ),
)
class BufferSweepBench {

  @Param(Array("4096", "8192", "16384", "32768", "65536", "131072", "262144", "524288", "1048576"))
  var bufSize: Int = _

  var rows: Vector[Mixed] = _
  var tmpFile: Path       = _

  @Setup(Level.Trial)
  def setupTrial(): Unit = {
    rows = BenchData.mixed(100_000)
  }

  @Setup(Level.Iteration)
  def setupIteration(): Unit = {
    tmpFile = Files.createTempFile("csvzen-bench-bufsweep-", ".csv")
  }

  @TearDown(Level.Iteration)
  def teardownIteration(): Unit = {
    Files.deleteIfExists(tmpFile)
  }

  @Benchmark
  def writeNullSink(): Unit = {
    val sink = new NullOutputStream
    val osw  = new OutputStreamWriter(sink, StandardCharsets.UTF_8)
    val bw   = new BufferedWriter(osw, bufSize)
    val w    = CsvWriter.unsafeFromWriter(bw, CsvConfig.default)
    try {
      w.writeHeader[Mixed]()
      w.writeAll(rows)
    } finally w.close()
  }

  @Benchmark
  def writeFile(): Unit = {
    val os  = Files.newOutputStream(
      tmpFile,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE,
    )
    val osw = new OutputStreamWriter(os, StandardCharsets.UTF_8)
    val bw  = new BufferedWriter(osw, bufSize)
    val w   = CsvWriter.unsafeFromWriter(bw, CsvConfig.default)
    try {
      w.writeHeader[Mixed]()
      w.writeAll(rows)
    } finally w.close()
  }
}
```

- [ ] **Step 2: Verify it compiles**

Run:

```bash
sbt --client "bench/Jmh/compile"
```

Expected: `[success]`.

- [ ] **Step 3: Smoke-test the sweep**

Run:

```bash
sbt --client "bench/Jmh/run -wi 1 -i 1 -f 1 -p bufSize=8192,65536 .*BufferSweepBench.writeNullSink.*"
```

Expected: two runs, one per `bufSize` value, both complete and report numbers.

- [ ] **Step 4: Commit**

```bash
git add modules/bench/src/main/scala/com/guizmaii/csvzen/bench/BufferSweepBench.scala
git commit -m "Add BufferSweepBench: 9-point buffer-size sweep on Mixed @ 100k"
```

---

## Task 9: Add the bench README

**Files:**
- Create: `modules/bench/README.md`

- [ ] **Step 1: Write the README**

Create `modules/bench/README.md`:

````markdown
# csvzen-bench

JMH benchmarks for csvzen. Private module — `publish / skip := true`. Not part
of the published artifact set; exists only to gate optimization phases on
measured deltas.

## Layout

- `CsvWriteBench` — main 24-cell bench (4 schemas × 3 sizes × 2 sinks)
  - `CsvWriteBenchSmallMedium`: 1k + 100k rows, `Throughput` mode
  - `CsvWriteBenchLarge`: 10M rows, `SingleShotTime` mode
- `BufferSweepBench` — 9-point buffer-size sweep on `Mixed @ 100k`, both sinks
- (P3 will add `QuotingThresholdBench`)
- (P4 will add `FastCsvBench` and `JacksonCsvBench`)

## Running

Bench JVM target is JDK 25 (per design doc). Set `JAVA_HOME` accordingly:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"   # macOS
# or
export JAVA_HOME="/usr/lib/jvm/java-25-openjdk"      # Linux
```

### Full main bench (all 24 cells, ~30 minutes total)

```bash
sbt --client "bench/Jmh/run -prof gc -prof stack -rf json -rff modules/bench/results/p0-baseline/csv-write.json com.guizmaii.csvzen.bench.CsvWriteBench.*"
```

### Buffer sweep (~10 minutes)

```bash
sbt --client "bench/Jmh/run -prof gc -rf json -rff modules/bench/results/p0-baseline/buffer-sweep.json com.guizmaii.csvzen.bench.BufferSweepBench.*"
```

### Quick single-cell smoke (use during development)

```bash
sbt --client "bench/Jmh/run -wi 2 -i 3 -f 1 .*intHeavy_medium_null.*"
```

## Profilers

`-prof gc` and `-prof stack` are the always-on diagnostic baseline. Use these
opt-in profilers when investigating a regression:

### async-profiler (allocations, macOS + Linux)

Install async-profiler first:

```bash
# macOS
brew install async-profiler

# Linux — download from https://github.com/async-profiler/async-profiler/releases
```

Then run with the `async` profiler:

```bash
sbt --client "bench/Jmh/run -prof async:event=alloc;dir=modules/bench/results/async/ .*doubleHeavy_medium_null.*"
```

This produces flame graphs showing where allocations originate.

### CPU sampling (Linux only)

```bash
sbt --client "bench/Jmh/run -prof async:event=cpu;interval=1ms;dir=modules/bench/results/async-cpu/ .*StringHeavy.*"
```

## Results

Per-phase results live under `modules/bench/results/<phase>/`. Each phase
commits:

- `csv-write.json` — raw JMH output for the 24-cell main bench
- `buffer-sweep.json` — raw JMH output for the buffer sweep (P0, P1, P3 only)
- `SUMMARY.md` — human-written narrative: hardware, JDK version, top-line
  numbers per cell, deltas vs. previous phase

The JSON is committed because per-phase comparisons need it. JSON is small
(< 100 KB per file).
````

- [ ] **Step 2: Commit**

```bash
git add modules/bench/README.md
git commit -m "Add csvzen-bench README with run instructions and profiler notes"
```

---

## Task 10: Run the full P0 baseline and capture results

Hardware-dependent step — the JSON output reflects whatever machine ran it. Capture machine details in the summary.

**Files:**
- Create: `modules/bench/results/p0-baseline/csv-write.json` (generated)
- Create: `modules/bench/results/p0-baseline/buffer-sweep.json` (generated)
- Create: `modules/bench/results/p0-baseline/SUMMARY.md`

- [ ] **Step 1: Confirm JDK 25 is set**

Run:

```bash
echo $JAVA_HOME
java -version
```

Expected: `JAVA_HOME` points at a JDK 25 install; `java -version` reports `25.x`. If not, set `JAVA_HOME` per the bench README before continuing.

- [ ] **Step 2: Run the main bench and capture JSON**

Run:

```bash
sbt --client "bench/Jmh/run -prof gc -prof stack -rf json -rff modules/bench/results/p0-baseline/csv-write.json com.guizmaii.csvzen.bench.CsvWriteBench.*"
```

Expected: completes in ~30 minutes (this is the full run — 24 benchmarks × 2 forks × (5 warmup + 10 measurement) iterations × 2s + the four large-size singleshot runs). On completion, sbt reports `[success]` and `csv-write.json` exists at the path passed to `-rff`.

If the run is interrupted, the JSON is *not* written. Restart from scratch — JMH does not resume.

- [ ] **Step 3: Run the buffer sweep and capture JSON**

Run:

```bash
sbt --client "bench/Jmh/run -prof gc -rf json -rff modules/bench/results/p0-baseline/buffer-sweep.json com.guizmaii.csvzen.bench.BufferSweepBench.*"
```

Expected: completes in ~10 minutes, writes `buffer-sweep.json`.

- [ ] **Step 4: Write the summary**

Create `modules/bench/results/p0-baseline/SUMMARY.md`. Use this template — fill in the actual numbers from the JSON outputs:

````markdown
# P0 baseline — csvzen pre-rewrite

**Hardware:** [e.g. MacBook Pro M3 Max, 64 GB, macOS 25.4]
**JDK:** [output of `java -version`]
**csvzen:** git SHA [output of `git rev-parse HEAD`]
**Date:** [today's date]

## Main bench (CsvWriteBench)

Throughput in ops/sec (small + medium); seconds/op (large). Allocation in B/op
from `gc.alloc.rate.norm`.

### Null sink

| schema       | small (1k) ops/s | small B/op | medium (100k) ops/s | medium B/op | large (10M) sec/op | large B/op |
|--------------|------------------|------------|---------------------|-------------|--------------------|------------|
| Mixed        |                  |            |                     |             |                    |            |
| IntHeavy     |                  |            |                     |             |                    |            |
| DoubleHeavy  |                  |            |                     |             |                    |            |
| StringHeavy  |                  |            |                     |             |                    |            |

### Tmp file

| schema       | small ops/s | small B/op | medium ops/s | medium B/op | large sec/op | large B/op |
|--------------|-------------|------------|--------------|-------------|--------------|------------|
| Mixed        |             |            |              |             |              |            |
| IntHeavy     |             |            |              |             |              |            |
| DoubleHeavy  |             |            |              |             |              |            |
| StringHeavy  |             |            |              |             |              |            |

## Buffer sweep (BufferSweepBench, Mixed @ 100k)

| bufSize | null-sink ops/s | file ops/s |
|---------|-----------------|------------|
| 4 KB    |                 |            |
| 8 KB    |                 |            |
| 16 KB   |                 |            |
| 32 KB   |                 |            |
| 64 KB   |                 |            |
| 128 KB  |                 |            |
| 256 KB  |                 |            |
| 512 KB  |                 |            |
| 1 MB    |                 |            |

**Optimum (informational):** [pick the row with highest ops/s on each sink]

## Notes

- These numbers measure the *current* Writer-backed encoder. P1 will replace
  it; the Writer-vs-byte[] delta lives in P1's SUMMARY.
- DoubleHeavy `gc.alloc.rate.norm` should be high (per-row `String` allocation
  from `Float.toString` / `Double.toString`). P2's Schubfach pass is what
  drops it to ~zero.
- StringHeavy null-sink `gc.alloc.rate.norm` reflects `BufferedWriter`'s
  internal allocation behaviour and any String escaping. Expected to drop to
  ~zero after P1.
````

- [ ] **Step 5: Verify the JSON outputs are present**

Run:

```bash
ls -la modules/bench/results/p0-baseline/
```

Expected:

```
csv-write.json
buffer-sweep.json
SUMMARY.md
```

- [ ] **Step 6: Commit**

```bash
git add modules/bench/results/p0-baseline/
git commit -m "Capture P0 baseline benchmarks against current Writer-backed encoder"
```

---

## Task 11: Final P0 verification

Confirm every DoD item from the design doc is satisfied.

- [ ] **Step 1: All tests pass**

Run:

```bash
sbt --client "test"
```

Expected: all suites pass — `core` (including the new `flushCount is reachable and starts at 0` test), `test-kit`, `zio`, `bench`.

- [ ] **Step 2: All format/lint checks pass**

Run:

```bash
sbt --client check
```

Expected: `scalafixAll --check`, `scalafmtCheckAll`, `scalafmtSbtCheck` all green.

- [ ] **Step 3: Confirm the deliverables**

Run:

```bash
ls modules/bench/results/p0-baseline/ && \
cat modules/bench/results/p0-baseline/SUMMARY.md | head -20
```

Expected: `csv-write.json`, `buffer-sweep.json`, `SUMMARY.md` all present; SUMMARY's hardware / JDK / SHA fields are filled in (not template placeholders).

- [ ] **Step 4: Confirm `flushCount` surface is in place**

Run:

```bash
grep -n "flushCount" modules/core/src/main/scala/com/guizmaii/csvzen/core/FieldEmitter.scala
```

Expected: one match on a `private[csvzen] def flushCount: Long = 0L` line.

- [ ] **Step 5: P0 done — no further commit**

P0 is complete. The next step is to invoke `superpowers:writing-plans` again, with the design doc + the freshly-captured `SUMMARY.md` as input, to generate the **P1 (byte[] foundation rewrite)** plan. Numbers from the SUMMARY (especially the buffer sweep curve and the per-cell allocation baselines) inform P1's targets:

- The optimum buffer size on the byte[] path will likely differ from this Writer-based curve; P1's `BufferSweepBench` rerun picks the new default.
- The `IntHeavy / null-sink` ops/sec from this baseline is the number P1 must beat by ≥ 2× (its DoD).
- The `DoubleHeavy / null-sink` `gc.alloc.rate.norm` from this baseline is what P2 must drop by ≥ 80 %.

---

## Out of scope for P0 (deliberate non-tasks)

- Any change to `FieldEmitter` internals beyond adding the `flushCount` stub.
- Any change to `CsvWriter.open`, `unsafeFromWriter`, or the ZIO sinks.
- Adding FastCSV / jackson-csv / uniVocity dependencies (P4 work).
- Implementing the SWAR / Vector API quoting scan (P3 work).
- Implementing in-buffer Schubfach (P2 work).
- Writing the README / CHANGELOG updates (P5 work).
- Bumping the global `javaTarget` from 17 to 21 (deferred to P5; the bench module overrides locally).

If anyone executing this plan finds themselves doing any of the above, stop — that work belongs to a later phase and lands with its own bench-gated DoD.
