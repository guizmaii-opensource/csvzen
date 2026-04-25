package com.guizmaii.csvzen.zio

import com.guizmaii.csvzen.core.{CsvConfig, CsvWriter}
import zio.{Scope, ZIO}

import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{OpenOption, Path}

/**
 * Opens a [[com.guizmaii.csvzen.core.CsvWriter]] inside a `Scope`, automatically
 * closing it when the scope exits (success, failure, or interruption). Mirrors the
 * defaults of `CsvWriter.open`: UTF-8 and, when no `options` are provided, the
 * standard `newBufferedWriter` open options (`CREATE`, `TRUNCATE_EXISTING`, `WRITE`).
 *
 * If you pass any `options`, they are forwarded as-is to `Files.newBufferedWriter`
 * and **replace** the defaults rather than adding on top of them. Callers who want
 * create/truncate/write semantics alongside a custom option (e.g. `APPEND`) must
 * include the desired `OpenOption`s explicitly.
 */
def openCsvWriter(
  path: Path,
  config: CsvConfig,
  charset: Charset = StandardCharsets.UTF_8,
  options: OpenOption*,
): ZIO[Scope, Throwable, CsvWriter] =
  ZIO.fromAutoCloseable(ZIO.attemptBlocking(CsvWriter.open(path, config, charset, options*)))
