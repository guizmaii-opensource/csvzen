package com.guizmaii.csvzen.core

/**
 * Minimal RFC 4180 CSV reader used exclusively to round-trip test output.
 * Not part of the public API. Not optimised. Handles `\r\n` and `\n` line endings
 * indiscriminately since we exercise both terminator configs against it.
 */
object TestCsvReader {

  def parse(input: String, config: CsvConfig = CsvConfig.default): Vector[Vector[String]] = {
    val delimiter = config.delimiter
    val quoteChar = config.quoteChar
    val rows      = Vector.newBuilder[Vector[String]]
    var row       = Vector.newBuilder[String]
    val cell      = new StringBuilder
    var i         = 0
    val n         = input.length
    var inQuotes  = false
    var rowHasAny = false

    def endCell(): Unit = {
      row += cell.toString
      cell.setLength(0)
      rowHasAny = true
    }
    def endRow(): Unit  = {
      // Always flush at least one cell per row, so a bare terminator maps to Vector("").
      endCell()
      rows += row.result()
      row = Vector.newBuilder[String]
      rowHasAny = false
    }

    while (i < n) {
      val c = input.charAt(i)
      if (inQuotes) {
        if (c == quoteChar) {
          if (i + 1 < n && input.charAt(i + 1) == quoteChar) {
            cell.append(quoteChar); i += 2
          } else {
            inQuotes = false; i += 1
          }
        } else {
          cell.append(c); i += 1
        }
      } else if (c == quoteChar) {
        inQuotes = true
        rowHasAny = true
        i += 1
      } else if (c == delimiter) {
        endCell(); i += 1
      } else if (c == '\r' && i + 1 < n && input.charAt(i + 1) == '\n') {
        endRow(); i += 2
      } else if (c == '\n') {
        endRow(); i += 1
      } else {
        cell.append(c)
        rowHasAny = true
        i += 1
      }
    }
    if (rowHasAny || cell.length > 0) endRow()
    rows.result()
  }
}
