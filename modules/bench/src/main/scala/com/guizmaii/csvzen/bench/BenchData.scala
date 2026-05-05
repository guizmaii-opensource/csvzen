package com.guizmaii.csvzen.bench

import com.guizmaii.csvzen.bench.Schemas.*

import java.time.Instant
import scala.util.Random

/**
 * Precomputed row vectors for benchmarks. All generators take a fixed seed so
 * every fork, every iteration, every JDK sees the same bytes — this is what
 * lets us compare phase-to-phase deltas without per-run noise.
 */
object BenchData {

  private val SeedMixed       = 1L
  private val SeedIntHeavy    = 2L
  private val SeedDoubleHeavy = 3L
  private val SeedStringHeavy = 4L

  def mixed(n: Int): Vector[Mixed] = {
    val r = new Random(SeedMixed)
    Vector.tabulate(n) { i =>
      Mixed(
        id = r.nextLong(),
        name = randAscii(r, 8 + r.nextInt(16)),
        count = r.nextInt(),
        amount = r.nextDouble() * 10000.0,
        ts = Instant.ofEpochMilli(1_700_000_000_000L + i.toLong * 1000L),
      )
    }
  }

  def intHeavy(n: Int): Vector[IntHeavy] = {
    val r = new Random(SeedIntHeavy)
    Vector.tabulate(n)(_ => IntHeavy(r.nextLong(), r.nextLong(), r.nextLong(), r.nextLong(), r.nextLong()))
  }

  def doubleHeavy(n: Int): Vector[DoubleHeavy] = {
    val r = new Random(SeedDoubleHeavy)
    Vector.tabulate(n) { _ =>
      DoubleHeavy(
        r.nextGaussian() * 1000.0,
        r.nextGaussian() * 1000.0,
        r.nextGaussian() * 1000.0,
        r.nextGaussian() * 1000.0,
        r.nextGaussian() * 1000.0,
      )
    }
  }

  def stringHeavy(n: Int): Vector[StringHeavy] = {
    val r = new Random(SeedStringHeavy)
    Vector.tabulate(n) { i =>
      // Every other row gets a comma in some field. This guarantees we hit
      // both the fast-path (no quoting) and the quoting path in equal measure.
      val needsQuoting    = (i % 2) == 0
      val len             = 8 + r.nextInt(24)
      def field(): String = {
        val s = randAscii(r, len)
        if (needsQuoting) s.updated(len / 2, ',') else s
      }
      StringHeavy(field(), field(), field(), field(), field())
    }
  }

  private val AsciiAlphaNum: Array[Char] = (('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9')).toArray

  private def randAscii(r: Random, len: Int): String = {
    val a = new Array[Char](len)
    var i = 0
    while (i < len) { a(i) = AsciiAlphaNum(r.nextInt(AsciiAlphaNum.length)); i += 1 }
    new String(a)
  }
}
