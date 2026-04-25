package com.guizmaii.csvzen.testkit

import com.guizmaii.csvzen.core.{CsvConfig, CsvRowEncoder, CsvWriter}
import zio.{Chunk, Tag, Trace, ZIO}
import zio.test.{Gen, Sized, Spec, TestArrow, TestEnvironment, TestResult, TestTrace, assertTrue, test}

import java.io.StringWriter
import java.nio.file.{Files, Path}

import com.guizmaii.csvzen.testkit.filehelpers.*

/**
 * Builds a golden test for `A`. Samples drawn from `gen` are encoded through
 * `CsvRowEncoder[A]` and the resulting CSV is compared against
 * `src/test/resources/golden/<relativePath>/<TypeShortName>.csv`.
 *
 * Workflow (mirrors `zio-json-golden`):
 *
 *   - First run with no golden file: writes `<Name>_new.csv`, fails with a message
 *     telling you to drop the `_new` suffix and re-run. That promotes the snapshot.
 *   - Subsequent runs: re-generate the samples (deterministic for a given `Gen` +
 *     `Sized` seed), compare to the on-disk file. On mismatch, write `<Name>_changed.csv`
 *     next to the original so you can diff. If the change is intentional, overwrite
 *     the original with `_changed`.
 *
 * Promotion is always an explicit file rename — no environment variable or system
 * property "auto-update" mode.
 */
def csvGoldenTest[A: Tag: CsvRowEncoder](
  gen: Gen[Sized, A]
)(using trace: Trace, config: GoldenConfiguration): Spec[TestEnvironment, Throwable] = {
  val name = getName[A]
  test(s"golden test for $name") {
    import config.{csvConfig, relativePath, sampleSize}
    for {
      resourceDir <- createGoldenDirectory(s"src/test/resources/golden/$relativePath")
      filePath     = resourceDir.resolve(s"$name.csv")
      exists      <- ZIO.attemptBlocking(Files.exists(filePath))
      assertion   <- if (exists) validateTest[A](resourceDir, name, gen, sampleSize, csvConfig)
                     else createNewTest[A](resourceDir, name, gen, sampleSize, csvConfig)
    } yield assertion
  }
}

private def validateTest[A: CsvRowEncoder](
  resourceDir: Path,
  name: String,
  gen: Gen[Sized, A],
  sampleSize: Int,
  csvConfig: CsvConfig,
)(using trace: Trace): ZIO[Sized, Throwable, TestResult] = {
  val filePath = resourceDir.resolve(s"$name.csv")
  for {
    currentCsv <- readCsvFromFile(filePath)
    samples    <- generateSample(gen, sampleSize)
    newCsv      = encodeSample(samples, csvConfig)
    assertion  <- if (newCsv == currentCsv) ZIO.succeed(assertTrue(newCsv == currentCsv))
                  else {
                    val changedPath = resourceDir.resolve(s"${name}_changed.csv")
                    writeCsvToFile(changedPath, newCsv) *>
                      ZIO.succeed(assertTrue(newCsv == currentCsv))
                  }
  } yield assertion
}

private def createNewTest[A: CsvRowEncoder](
  resourceDir: Path,
  name: String,
  gen: Gen[Sized, A],
  sampleSize: Int,
  csvConfig: CsvConfig,
)(using trace: Trace): ZIO[Sized, Throwable, TestResult] = {
  val newPath       = resourceDir.resolve(s"${name}_new.csv")
  val failureString =
    s"No existing golden test for ${resourceDir.resolve(s"$name.csv")}. Remove _new from the suffix and re-run the test."

  for {
    samples  <- generateSample(gen, sampleSize)
    csv       = encodeSample(samples, csvConfig)
    _        <- ZIO
                  .ifZIO(ZIO.attemptBlocking(Files.exists(newPath)))(ZIO.unit, ZIO.attemptBlocking(Files.createFile(newPath)))
    _        <- writeCsvToFile(newPath, csv)
    assertion = TestArrow.make((_: Any) => TestTrace.fail(failureString).withLocation(Some(trace.toString)))
  } yield TestResult(assertion)
}

/** Implementation inspired by zio-test [[zio.test#check]]. */
private def generateSample[A](
  gen: Gen[Sized, A],
  sampleSize: Int,
)(using trace: Trace): ZIO[Sized, Throwable, Chunk[A]] =
  gen.sample.forever
    .map(_.value)
    .take(sampleSize.toLong)
    .runCollect

private def encodeSample[A: CsvRowEncoder](
  samples: Chunk[A],
  csvConfig: CsvConfig,
): String = {
  val sw     = new StringWriter
  val writer = CsvWriter.unsafeFromWriter(sw, csvConfig)
  try {
    writer.writeHeader[A]()
    writer.writeAll(samples)
  } finally writer.close()
  sw.toString
}

private def getName[A](using tag: Tag[A]): String = tag.tag.shortName
