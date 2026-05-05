package com.guizmaii.csvzen.bench

import zio.test.*

object BenchDataSpec extends ZIOSpecDefault {
  override def spec = suite("BenchData")(
    test("mixed(n) returns exactly n rows") {
      assertTrue(BenchData.mixed(1000).size == 1000)
    },
    test("intHeavy(n) returns exactly n rows") {
      assertTrue(BenchData.intHeavy(1000).size == 1000)
    },
    test("doubleHeavy(n) returns exactly n rows") {
      assertTrue(BenchData.doubleHeavy(1000).size == 1000)
    },
    test("stringHeavy(n) returns exactly n rows") {
      assertTrue(BenchData.stringHeavy(1000).size == 1000)
    },
    test("stringHeavy: roughly half the rows contain a comma") {
      val rows      = BenchData.stringHeavy(1000)
      val withComma =
        rows.count(r => r.a.contains(',') || r.b.contains(',') || r.c.contains(',') || r.d.contains(',') || r.e.contains(','))
      assertTrue(withComma >= 400 && withComma <= 600)
    },
    test("two calls with the same n return identical data (fixed seed)") {
      assertTrue(BenchData.mixed(100) == BenchData.mixed(100))
    },
  )
}
