package com.guizmaii.csvzen.bench

import java.io.OutputStream

/**
 * `OutputStream` that discards all bytes and only tracks how many were
 * written. Used by null-sink benchmarks to isolate encoder cost from disk
 * IO. Single-threaded — JMH benchmarks are single-threaded by default.
 */
final class NullOutputStream extends OutputStream {

  private var count: Long = 0L

  override def write(b: Int): Unit = count += 1L

  override def write(b: Array[Byte]): Unit = count += b.length.toLong

  override def write(b: Array[Byte], off: Int, len: Int): Unit = count += len.toLong

  def bytesWritten: Long = count

  def reset(): Unit = count = 0L
}
