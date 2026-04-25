package com.guizmaii.csvzen.core

import java.io.{Flushable, Writer}
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{Files, OpenOption, Path}

/**
 * Streaming CSV writer.
 *
 * Not thread-safe: a single instance must not be used concurrently from multiple threads.
 * The underlying `FieldEmitter` holds mutable per-row state.
 *
 * Unusable after any `IOException` from the underlying `Writer`. csvzen does not attempt
 * to recover from partial row writes; a caller that catches a mid-row failure must close
 * the writer and discard the file rather than writing further rows.
 */
final class CsvWriter private[core] (out: Writer, config: CsvConfig) extends AutoCloseable with Flushable {

  private[core] val emitter: FieldEmitter = new FieldEmitter(out, config)

  inline def writeRow[A](a: A)(using enc: CsvRowEncoder[A]): Unit = {
    emitter.beginRow()
    enc.encode(a, emitter)
    emitter.endRow()
  }

  inline def writeRow(inline body: FieldEmitter => Unit): Unit = {
    emitter.beginRow()
    body(emitter)
    emitter.endRow()
  }

  inline def writeHeader[A]()(using enc: CsvRowEncoder[A]): Unit = writeHeader(enc.headerNames)

  def writeHeader(names: IndexedSeq[String]): Unit = {
    emitter.beginRow()
    val n = names.length
    var i = 0
    while (i < n) {
      emitter.emitString(names(i))
      i += 1
    }
    emitter.endRow()
  }

  inline def writeAll[A](rows: Iterable[A])(using enc: CsvRowEncoder[A]): Unit = {
    val it = rows.iterator
    while (it.hasNext) writeRow(it.next())
  }

  /** Flushes the underlying `Writer`, pushing buffered bytes to disk. */
  override def flush(): Unit = out.flush()

  /** Flushes and closes the underlying `Writer` (`Writer.close` flushes before closing). */
  override def close(): Unit = out.close()
}

object CsvWriter {

  /**
   * Opens a buffered CSV writer on `path`. Defaults to UTF-8 and the standard
   * `newBufferedWriter` open options (`CREATE`, `TRUNCATE_EXISTING`, `WRITE`). Pass a
   * different `charset` for e.g. ISO-8859-1, and additional `options` such as
   * `StandardOpenOption.APPEND` to control create/truncate/append behaviour.
   */
  def open(
    path: Path,
    config: CsvConfig,
    charset: Charset = StandardCharsets.UTF_8,
    options: OpenOption*,
  ): CsvWriter =
    new CsvWriter(Files.newBufferedWriter(path, charset, options*), config)

  private[csvzen] def unsafeFromWriter(out: Writer, config: CsvConfig): CsvWriter =
    new CsvWriter(out, config)
}
