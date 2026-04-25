package com.guizmaii.csvzen.core

import java.io.Writer

/**
 * Typed, mostly-zero-allocation CSV field emitter. Each `emit*` call writes a
 * self-contained field, automatically prefixing the delimiter when it is not the first
 * field of the current row.
 *
 * Not thread-safe: holds mutable per-row state (`first` flag and integer scratch buffer);
 * one instance must not be used concurrently from multiple threads.
 */
final class FieldEmitter private[core] (out: Writer, config: CsvConfig) {

  private val delimiter: Char        = config.delimiter
  private val quoteChar: Char        = config.quoteChar
  private val lineTerminator: String = config.lineTerminator
  private var first: Boolean         = true
  private val scratch: Array[Char]   = new Array[Char](20)

  private[core] def beginRow(): Unit = first = true

  private[core] def endRow(): Unit = out.write(lineTerminator)

  private def fieldPrelude(): Unit =
    if (!first) out.write(delimiter.toInt)
    else first = false

  def emitEmpty(): Unit = fieldPrelude()

  def emitString(s: String): Unit = {
    fieldPrelude()
    writeEscaped(s)
  }

  def emitBoolean(b: Boolean): Unit = {
    fieldPrelude()
    out.write(if (b) "true" else "false")
  }

  def emitInt(i: Int): Unit = {
    fieldPrelude()
    writeInt(i)
  }

  def emitLong(l: Long): Unit = {
    fieldPrelude()
    writeLong(l)
  }

  def emitShort(s: Short): Unit = {
    fieldPrelude()
    writeInt(s.toInt)
  }

  def emitByte(b: Byte): Unit = {
    fieldPrelude()
    writeInt(b.toInt)
  }

  def emitChar(c: Char): Unit = {
    fieldPrelude()
    writeEscapedChar(c)
  }

  def emitFloat(f: Float): Unit = {
    fieldPrelude()
    out.write(java.lang.Float.toString(f))
  }

  def emitDouble(d: Double): Unit = {
    fieldPrelude()
    out.write(java.lang.Double.toString(d))
  }

  // Process digits in the non-positive domain so Int.MinValue / Long.MinValue need no
  // special case (their absolute value has no positive Int/Long representation).
  private def writeInt(i: Int): Unit = {
    var n    = i
    if (n < 0) out.write('-'.toInt)
    else n = -n
    var pos  = scratch.length
    var cont = true
    while (cont) {
      pos -= 1
      scratch(pos) = ('0' - (n % 10)).toChar
      n /= 10
      cont = n != 0
    }
    out.write(scratch, pos, scratch.length - pos)
  }

  private def writeLong(l: Long): Unit = {
    var n    = l
    if (n < 0L) out.write('-'.toInt)
    else n = -n
    var pos  = scratch.length
    var cont = true
    while (cont) {
      pos -= 1
      scratch(pos) = ('0' - (n % 10L)).toChar
      n /= 10L
      cont = n != 0L
    }
    out.write(scratch, pos, scratch.length - pos)
  }

  private def writeEscaped(s: String): Unit = {
    val len          = s.length
    var needsQuoting = false
    var hasQuote     = false
    var i            = 0
    while (i < len) {
      val c = s.charAt(i)
      // Check quoteChar first: if a pathological config aliases it with the
      // delimiter or a line terminator, we must still mark hasQuote so that the
      // emission pass doubles the embedded quotes.
      if (c == quoteChar) {
        needsQuoting = true
        hasQuote = true
      } else if (c == delimiter || c == '\r' || c == '\n') needsQuoting = true
      i += 1
    }
    if (!needsQuoting) out.write(s)
    else {
      out.write(quoteChar.toInt)
      if (hasQuote) {
        i = 0
        while (i < len) {
          val c = s.charAt(i)
          if (c == quoteChar) out.write(quoteChar.toInt)
          out.write(c.toInt)
          i += 1
        }
      } else out.write(s)
      out.write(quoteChar.toInt)
    }
  }

  private def writeEscapedChar(c: Char): Unit = {
    val needsQuoting = c == delimiter || c == quoteChar || c == '\r' || c == '\n'
    if (!needsQuoting) out.write(c.toInt)
    else {
      out.write(quoteChar.toInt)
      if (c == quoteChar) out.write(quoteChar.toInt)
      out.write(c.toInt)
      out.write(quoteChar.toInt)
    }
  }
}
