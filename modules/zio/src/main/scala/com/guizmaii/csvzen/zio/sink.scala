package com.guizmaii.csvzen.zio

import com.guizmaii.csvzen.core.{CsvConfig, CsvRowEncoder, CsvWriter}
import zio.{Chunk, ZIO, ZNothing}
import zio.stream.{ZChannel, ZSink}

import scala.annotation.threadUnsafe

import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{OpenOption, Path}

/**
 * A `ZSink` that writes a stream of `A` to `path` as CSV: one header row first,
 * then one row per `A`. The terminal value is the number of rows written
 * (header excluded).
 *
 * The underlying `CsvWriter` is opened in a `Scope` and closed at sink completion
 * (success, failure, or interruption). Use this with any
 * `ZStream[Any, Throwable, A]`:
 *
 * {{{
 * stream.run(csvSink[Person](path, CsvConfig.default))
 * }}}
 *
 * '''Blocking executor.''' Setup (open + write header) and each per-chunk write
 * use `ZIO.attemptBlocking`, so the file IO never runs on the default (CPU-bound)
 * executor. For the strongest guarantee — keeping the channel pump itself on
 * the blocking pool between chunks, no executor ping-pong — wrap the run call:
 *
 * {{{
 * ZIO.blocking(stream.run(csvSink[Person](path, CsvConfig.default)))
 * }}}
 *
 * That locks the entire sink-driven effect to the blocking executor for its
 * full lifetime, which matters for hot streams where the per-chunk shift adds
 * up.
 */
def csvSink[A](
  path: Path,
  config: CsvConfig,
  charset: Charset = StandardCharsets.UTF_8,
  options: OpenOption*,
)(using enc: CsvRowEncoder[A]): ZSink[Any, Throwable, A, Nothing, Long] =
  ZSink.unwrapScoped {
    // Register the writer with the Scope BEFORE writing the header. If we bundled
    // `writeHeader` into the same `attemptBlocking` as `open`, a throw from the
    // header write would fail the acquire effect and `fromAutoCloseable` would
    // never register a close finalizer — the just-opened writer would leak.
    ZIO.blocking {
      ZIO
        .fromAutoCloseable(ZIO.attempt(CsvWriter.open(path, config, charset, options*)))
        .flatMap { writer =>
          ZIO
            .attempt(writer.writeHeader[A]())
            .as {
              var count = 0L

              @threadUnsafe
              lazy val loop: ZChannel[Any, ZNothing, Chunk[A], Any, Throwable, Chunk[Nothing], Long] =
                ZChannel.readWithCause(
                  in = chunk =>
                    ZChannel.fromZIO(ZIO.attemptBlocking {
                      writer.writeAll(chunk)
                      count += chunk.size
                    }) *> loop,
                  halt = cause => ZChannel.refailCause(cause),
                  done = _ => ZChannel.succeedNow(count),
                )

              ZSink.fromChannel(loop)
            }
        }
    }
  }
