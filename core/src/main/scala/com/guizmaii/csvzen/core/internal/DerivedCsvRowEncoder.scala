package com.guizmaii.csvzen.core
package internal

import scala.collection.immutable.ArraySeq

final class DerivedCsvRowEncoder[A <: Product](
  labels: Array[String],
  encoders: Array[CsvFieldEncoder[Any]],
) extends CsvRowEncoder[A] {

  private val n: Int = encoders.length

  val headerNames: IndexedSeq[String] = ArraySeq.unsafeWrapArray(labels)

  def encode(a: A, out: FieldEmitter): Unit = {
    var i = 0
    while (i < n) {
      encoders(i).encode(a.productElement(i), out)
      i += 1
    }
  }
}
