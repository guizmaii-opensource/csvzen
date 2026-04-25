package com.guizmaii.csvzen.core

final case class CsvConfig(
  delimiter: Char = ',',
  quoteChar: Char = '"',
  lineTerminator: String = "\r\n",
) {
  require(delimiter != quoteChar, "delimiter must differ from quoteChar")
  require(delimiter != '\r' && delimiter != '\n', "delimiter must not be CR or LF")
  require(quoteChar != '\r' && quoteChar != '\n', "quoteChar must not be CR or LF")
  require(
    lineTerminator == "\n" || lineTerminator == "\r" || lineTerminator == "\r\n",
    "lineTerminator must be one of \"\\n\", \"\\r\", or \"\\r\\n\"",
  )
}

object CsvConfig {
  val default: CsvConfig = CsvConfig()
}
