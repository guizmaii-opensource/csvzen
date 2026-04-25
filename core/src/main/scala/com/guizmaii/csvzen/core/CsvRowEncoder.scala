package com.guizmaii.csvzen.core

import scala.annotation.nowarn
import scala.compiletime.{constValueTuple, summonAll}
import scala.deriving.Mirror

trait CsvRowEncoder[-A] {
  def headerNames: IndexedSeq[String]
  def encode(a: A, out: FieldEmitter): Unit
}

object CsvRowEncoder {

  inline def derived[A <: Product](using m: Mirror.ProductOf[A]): CsvRowEncoder[A] = {
    val labels   = labelsOf[m.MirroredElemLabels]
    val encoders = encodersOf[m.MirroredElemTypes]
    new internal.DerivedCsvRowEncoder[A](labels, encoders)
  }

  @nowarn("id=E197")
  inline def custom[A](
    inline headers: IndexedSeq[String]
  )(inline encodeFn: (A, FieldEmitter) => Unit): CsvRowEncoder[A] =
    new CsvRowEncoder[A] {
      override val headerNames: IndexedSeq[String]       = headers
      override def encode(a: A, out: FieldEmitter): Unit = encodeFn(a, out)
    }

  inline private def labelsOf[L <: Tuple]: Array[String] = {
    val arr = constValueTuple[L].toArray
    val out = new Array[String](arr.length)
    var i   = 0
    while (i < arr.length) {
      out(i) = arr(i).asInstanceOf[String]
      i += 1
    }
    out
  }

  // Unsafe cast: contravariance makes `CsvFieldEncoder[X] <: CsvFieldEncoder[Any]` a
  // narrowing, not a widening. Safe here because DerivedCsvRowEncoder.encode only ever
  // feeds slot i the matching `productElement(i)` value for that position.
  inline private def encodersOf[E <: Tuple]: Array[CsvFieldEncoder[Any]] = {
    val arr = summonAll[Tuple.Map[E, CsvFieldEncoder]].toArray
    val out = new Array[CsvFieldEncoder[Any]](arr.length)
    var i   = 0
    while (i < arr.length) {
      out(i) = arr(i).asInstanceOf[CsvFieldEncoder[Any]]
      i += 1
    }
    out
  }
}
