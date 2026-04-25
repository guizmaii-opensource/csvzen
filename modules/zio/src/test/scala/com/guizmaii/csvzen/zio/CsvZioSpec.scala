package com.guizmaii.csvzen.zio

import com.guizmaii.csvzen.core.{CsvConfig, CsvRowEncoder}
import zio.{Chunk, Scope, ZIO, durationInt}
import zio.stream.ZStream
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object CsvZioSpec extends ZIOSpecDefault {

  private final case class Person(name: String, age: Int) derives CsvRowEncoder

  private def withTmpFile[R, A](use: Path => ZIO[R, Throwable, A]): ZIO[R, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempFile("csvzen-zio-", ".csv"))
    )(p => ZIO.attemptBlocking(Files.deleteIfExists(p)).ignore)(use)

  private def readUtf8(p: Path): ZIO[Any, Throwable, String] =
    ZIO.attemptBlocking(Files.readString(p, StandardCharsets.UTF_8))

  private val managedSpec =
    suite("openCsvWriter")(
      test("opens a CsvWriter, lets the body write rows, and closes it at scope exit") {
        withTmpFile { path =>
          ZIO
            .scoped(
              openCsvWriter(path, CsvConfig.default).flatMap(w =>
                ZIO.attemptBlocking {
                  w.writeHeader[Person]()
                  w.writeRow(Person("Ada", 36))
                  w.writeRow(Person("Linus", 55))
                }
              )
            )
            .zipRight(readUtf8(path))
            .map(contents => assertTrue(contents == "name,age\r\nAda,36\r\nLinus,55\r\n"))
        }
      }
    )

  private val sinkSpec =
    suite("csvSink")(
      test("consumes a ZStream of A, writes header + rows, returns the row count") {
        withTmpFile { path =>
          val rows = Vector(Person("Ada", 36), Person("Linus", 55), Person("Grace", 85))
          val sink = csvSink[Person](path, CsvConfig.default)
          ZStream
            .fromIterable(rows)
            .run(sink)
            .flatMap(count =>
              readUtf8(path).map(contents =>
                assertTrue(
                  count == 3L,
                  contents == "name,age\r\nAda,36\r\nLinus,55\r\nGrace,85\r\n",
                )
              )
            )
        }
      },
      test("writes only the header row when the stream is empty") {
        withTmpFile { path =>
          val sink = csvSink[Person](path, CsvConfig.default)
          ZStream.empty
            .run(sink)
            .flatMap(count => readUtf8(path).map(contents => assertTrue(count == 0L, contents == "name,age\r\n")))
        }
      },
      // Forces multiple chunks through the channel to lock in the running-count
      // recursion: each chunk must contribute its size to the running total, not
      // overwrite it. `ZStream.fromIterable` would emit a single chunk and hide
      // the bug.
      test("counts rows correctly across multiple chunks") {
        withTmpFile { path =>
          val chunk1 = Chunk(Person("Ada", 36), Person("Linus", 55))
          val chunk2 = Chunk(Person("Grace", 85))
          val chunk3 = Chunk(Person("Edsger", 72), Person("Alan", 41), Person("Donald", 86))
          val sink   = csvSink[Person](path, CsvConfig.default)
          ZStream
            .fromChunks(chunk1, chunk2, chunk3)
            .run(sink)
            .flatMap(count =>
              readUtf8(path).map(contents =>
                assertTrue(
                  count == 6L,
                  contents ==
                    "name,age\r\n" +
                    "Ada,36\r\nLinus,55\r\n" +
                    "Grace,85\r\n" +
                    "Edsger,72\r\nAlan,41\r\nDonald,86\r\n",
                )
              )
            )
        }
      },
      // Failure-path: when the upstream fails after the sink has already written
      // some rows, the run must propagate the failure AND the writer must have
      // been closed (otherwise we'd be sitting on a leaked file handle and the
      // partial output would be invisible until GC).
      test("propagates upstream failures and closes the writer") {
        withTmpFile { path =>
          val boom   = new RuntimeException("boom")
          val sink   = csvSink[Person](path, CsvConfig.default)
          val stream = ZStream.fromIterable(Vector(Person("Ada", 36))) ++ ZStream.fail(boom)
          stream
            .run(sink)
            .either
            .flatMap(result =>
              readUtf8(path).map(contents =>
                assertTrue(
                  // run failed with our boom
                  result.left.toOption.exists(_.getMessage == "boom"),
                  // writer was finalised: the partial output (header + Ada) is
                  // flushed to disk and the file is readable
                  contents == "name,age\r\nAda,36\r\n",
                )
              )
            )
        }
      },
      // Interruption-path: the same Scope finalizer must fire when the fiber is
      // interrupted mid-pull. We use ZStream.never so the sink only ever writes
      // the header, then we interrupt and read the file back. `fiber.interrupt`
      // returns once finalisers have run, so reading after it is race-free.
      // The sleep escapes TestClock via `Live.live` — without that, ZIOSpecDefault's
      // TestClock would never advance and the sleep would hang.
      test("releases the writer on fiber interruption") {
        withTmpFile { path =>
          val sink = csvSink[Person](path, CsvConfig.default)
          ZStream.never
            .run(sink)
            .fork
            .flatMap(fiber => Live.live(ZIO.sleep(50.millis)).zipRight(fiber.interrupt))
            .zipRight(readUtf8(path))
            .map(contents => assertTrue(contents == "name,age\r\n"))
        }
      },
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("csvzen-zio")(managedSpec, sinkSpec)
}
