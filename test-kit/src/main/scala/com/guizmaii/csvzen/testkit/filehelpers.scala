package com.guizmaii.csvzen.testkit

import zio.{IO, Task, Trace, ZIO}

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
    if (file.getName == "target") ZIO.succeed(file)
    else ZIO.attempt(file.getParentFile).flatMap(getRootDir)

  def createGoldenDirectory(pathToDir: String)(using trace: Trace): Task[Path] = {
    val rootFile =
      try new File(getClass.getResource("/").toURI)
      catch {
        case _: IllegalArgumentException => new File(getClass.getResource(".").toURI)
      }
    for {
      baseFile <- getRootDir(rootFile)
      goldenDir = new File(baseFile.getParentFile, pathToDir)
      _        <- ZIO.attemptBlocking(goldenDir.mkdirs())
    } yield goldenDir.toPath
  }

  def writeCsvToFile(path: Path, csv: String)(using trace: Trace): IO[IOException, Unit] =
    ZIO.attemptBlockingIO(Files.write(path, csv.getBytes(StandardCharsets.UTF_8))).unit

  def readCsvFromFile(path: Path)(using trace: Trace): Task[String] =
    ZIO.attemptBlocking(Files.readString(path, StandardCharsets.UTF_8))
}
