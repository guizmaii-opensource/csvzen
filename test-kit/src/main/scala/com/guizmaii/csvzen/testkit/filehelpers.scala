package com.guizmaii.csvzen.testkit

import zio.{IO, Task, Trace, UIO, ZIO}

import java.io.{File, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

private[testkit] object filehelpers {

  /**
   * Walks up the file tree from a class-loader resource location until it finds the
   * `target/` directory. The directory immediately above is the project (or sub-module)
   * root, which is where `src/test/resources/golden/...` lives. Mirrors the approach
   * `zio-json-golden` uses.
   */
  def getRootDir(file: File)(using trace: Trace): Task[File] =
    if (file == null)
      ZIO.fail(
        new IllegalStateException(
          "csvzen-test-kit could not locate a `target/` directory above the test resources. " +
            "It assumes an sbt-style project layout (test classes under `<module>/target/...`)."
        )
      )
    else if (file.getName == "target") ZIO.succeed(file)
    else ZIO.attempt(file.getParentFile).flatMap(getRootDir)

  def createGoldenDirectory(pathToDir: String)(using trace: Trace): Task[Path] = {
    // Resolve a `File` for either the classpath root (`/`) or the package root (`.`),
    // tolerating both the `null` URL (some classloaders) and non-hierarchical URIs
    // (e.g. JAR-packaged tests, where `new File(uri)` throws `IllegalArgumentException`).
    def tryRoot(name: String): Option[File] =
      Option(getClass.getResource(name)).flatMap { url =>
        try Some(new File(url.toURI))
        catch {
          case _: IllegalArgumentException    => None
          case _: java.net.URISyntaxException => None
        }
      }
    val rootFileOpt                         = tryRoot("/").orElse(tryRoot("."))
    for {
      rootFile <- ZIO
                    .fromOption(rootFileOpt)
                    .orElseFail(new IllegalStateException("csvzen-test-kit: no file-based classpath root resource available."))
      baseFile <- getRootDir(rootFile)
      goldenDir = new File(baseFile.getParentFile, pathToDir)
      _        <- ZIO.attemptBlocking(goldenDir.mkdirs())
    } yield goldenDir.toPath
  }

  def writeCsvToFile(path: Path, csv: String)(using trace: Trace): IO[IOException, Unit] =
    ZIO.attemptBlockingIO(Files.write(path, csv.getBytes(StandardCharsets.UTF_8))).unit

  def readCsvFromFile(path: Path)(using trace: Trace): Task[String] =
    ZIO.attemptBlocking(Files.readString(path, StandardCharsets.UTF_8))

  /**
   * Best-effort delete: missing path is fine, and any `SecurityException` /
   * `IOException` (e.g. a locked file on Windows) is swallowed so a janky cleanup
   * never fails an otherwise-green test.
   */
  def deleteIfExists(path: Path)(using trace: Trace): UIO[Unit] =
    ZIO.attemptBlocking { Files.deleteIfExists(path); () }.ignore
}
