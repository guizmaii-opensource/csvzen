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
  value = 2,
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

  var mixedSmall: Vector[Mixed]              = _
  var mixedMedium: Vector[Mixed]             = _
  var intHeavySmall: Vector[IntHeavy]        = _
  var intHeavyMedium: Vector[IntHeavy]       = _
  var doubleHeavySmall: Vector[DoubleHeavy]  = _
  var doubleHeavyMedium: Vector[DoubleHeavy] = _
  var stringHeavySmall: Vector[StringHeavy]  = _
  var stringHeavyMedium: Vector[StringHeavy] = _

  var tmpFile: Path = _

  @Setup(Level.Trial)
  def setupTrial(): Unit = {
    mixedSmall = BenchData.mixed(1_000)
    mixedMedium = BenchData.mixed(100_000)
    intHeavySmall = BenchData.intHeavy(1_000)
    intHeavyMedium = BenchData.intHeavy(100_000)
    doubleHeavySmall = BenchData.doubleHeavy(1_000)
    doubleHeavyMedium = BenchData.doubleHeavy(100_000)
    stringHeavySmall = BenchData.stringHeavy(1_000)
    stringHeavyMedium = BenchData.stringHeavy(100_000)
  }

  @Setup(Level.Iteration)
  def setupIteration(): Unit =
    tmpFile = Files.createTempFile("csvzen-bench-", ".csv")

  @TearDown(Level.Iteration)
  def teardownIteration(): Unit = { val _ = Files.deleteIfExists(tmpFile) }

  // ---- Helpers -------------------------------------------------------------

  /**
   * Writes header + all rows to a `NullOutputStream` wrapped in an
   * `OutputStreamWriter`/`BufferedWriter` chain that mirrors what
   * `CsvWriter.open` does on a real file. This is the *current*
   * `Writer`-backed shape — P1 will replace `unsafeFromWriter` with
   * `unsafeFromOutputStream` and this helper goes away.
   */
  inline private def writeAllNullSink[A](
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

  inline private def writeAllFile[A](
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

  @Benchmark def mixed_small_null: Unit       = writeAllNullSink(mixedSmall)
  @Benchmark def intHeavy_small_null: Unit    = writeAllNullSink(intHeavySmall)
  @Benchmark def doubleHeavy_small_null: Unit = writeAllNullSink(doubleHeavySmall)
  @Benchmark def stringHeavy_small_null: Unit = writeAllNullSink(stringHeavySmall)

  // ---- Benchmarks: null sink, medium ---------------------------------------

  @Benchmark def mixed_medium_null: Unit       = writeAllNullSink(mixedMedium)
  @Benchmark def intHeavy_medium_null: Unit    = writeAllNullSink(intHeavyMedium)
  @Benchmark def doubleHeavy_medium_null: Unit = writeAllNullSink(doubleHeavyMedium)
  @Benchmark def stringHeavy_medium_null: Unit = writeAllNullSink(stringHeavyMedium)

  // ---- Benchmarks: tmp file, small ----------------------------------------

  @Benchmark def mixed_small_file: Unit       = writeAllFile(mixedSmall)
  @Benchmark def intHeavy_small_file: Unit    = writeAllFile(intHeavySmall)
  @Benchmark def doubleHeavy_small_file: Unit = writeAllFile(doubleHeavySmall)
  @Benchmark def stringHeavy_small_file: Unit = writeAllFile(stringHeavySmall)

  // ---- Benchmarks: tmp file, medium ---------------------------------------

  @Benchmark def mixed_medium_file: Unit       = writeAllFile(mixedMedium)
  @Benchmark def intHeavy_medium_file: Unit    = writeAllFile(intHeavyMedium)
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
  value = 1,
  jvmArgs = Array(
    "-Xms4g",
    "-Xmx4g",
    "-XX:+UseG1GC",
    "--add-modules",
    "jdk.incubator.vector",
  ),
)
class CsvWriteBenchLarge {

  var mixedLarge: Vector[Mixed]             = _
  var intHeavyLarge: Vector[IntHeavy]       = _
  var doubleHeavyLarge: Vector[DoubleHeavy] = _
  var stringHeavyLarge: Vector[StringHeavy] = _

  var tmpFile: Path = _

  @Setup(Level.Trial)
  def setupTrial(): Unit = {
    mixedLarge = BenchData.mixed(10_000_000)
    intHeavyLarge = BenchData.intHeavy(10_000_000)
    doubleHeavyLarge = BenchData.doubleHeavy(10_000_000)
    stringHeavyLarge = BenchData.stringHeavy(10_000_000)
  }

  @Setup(Level.Invocation)
  def setupInvocation(): Unit =
    tmpFile = Files.createTempFile("csvzen-bench-", ".csv")

  @TearDown(Level.Invocation)
  def teardownInvocation(): Unit = { val _ = Files.deleteIfExists(tmpFile) }

  inline private def writeAllNullSink[A](
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

  inline private def writeAllFile[A](
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

  @Benchmark def mixed_large_null: Unit       = writeAllNullSink(mixedLarge)
  @Benchmark def intHeavy_large_null: Unit    = writeAllNullSink(intHeavyLarge)
  @Benchmark def doubleHeavy_large_null: Unit = writeAllNullSink(doubleHeavyLarge)
  @Benchmark def stringHeavy_large_null: Unit = writeAllNullSink(stringHeavyLarge)

  @Benchmark def mixed_large_file: Unit       = writeAllFile(mixedLarge)
  @Benchmark def intHeavy_large_file: Unit    = writeAllFile(intHeavyLarge)
  @Benchmark def doubleHeavy_large_file: Unit = writeAllFile(doubleHeavyLarge)
  @Benchmark def stringHeavy_large_file: Unit = writeAllFile(stringHeavyLarge)
}
