package com.guizmaii.csvzen.bench

import zio.test.*

object NullOutputStreamSpec extends ZIOSpecDefault {
  override def spec = suite("NullOutputStream")(
    test("counts single-byte writes") {
      val s = new NullOutputStream
      s.write(1); s.write(2); s.write(3)
      assertTrue(s.bytesWritten == 3L)
    },
    test("counts array writes") {
      val s = new NullOutputStream
      s.write(Array[Byte](1, 2, 3, 4))
      assertTrue(s.bytesWritten == 4L)
    },
    test("counts array slice writes") {
      val s = new NullOutputStream
      s.write(Array[Byte](1, 2, 3, 4, 5), 1, 3)
      assertTrue(s.bytesWritten == 3L)
    },
    test("reset() zeroes the counter") {
      val s = new NullOutputStream
      s.write(Array[Byte](1, 2, 3))
      s.reset()
      assertTrue(s.bytesWritten == 0L)
    },
  )
}
