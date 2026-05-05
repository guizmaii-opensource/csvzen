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
  value = 2,
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
  def setupTrial(): Unit =
    rows = BenchData.mixed(100_000)

  @Setup(Level.Iteration)
  def setupIteration(): Unit =
    tmpFile = Files.createTempFile("csvzen-bench-bufsweep-", ".csv")

  @TearDown(Level.Iteration)
  def teardownIteration(): Unit = { val _ = Files.deleteIfExists(tmpFile) }

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
