package com.guizmaii.csvzen.core

import zio.Scope
import zio.test.*

import java.io.StringWriter

object CsvRowEncoderSpec extends ZIOSpecDefault {

  private def writeRow[A](a: A)(using enc: CsvRowEncoder[A]): String = {
    val sw = new StringWriter
    val w  = CsvWriter.unsafeFromWriter(sw, CsvConfig.default)
    try w.writeRow(a)
    finally w.close()
    sw.toString
  }

  private def header[A](using enc: CsvRowEncoder[A]): IndexedSeq[String] = enc.headerNames

  private final case class One(a: Int) derives CsvRowEncoder
  private final case class Two(a: Int, b: String) derives CsvRowEncoder
  private final case class Five(a: Int, b: String, c: Boolean, d: Long, e: Option[String]) derives CsvRowEncoder
  private final case class Ten(
    a: Int,
    b: Int,
    c: Int,
    d: Int,
    e: Int,
    f: Int,
    g: Int,
    h: Int,
    i: Int,
    j: Int,
  ) derives CsvRowEncoder

  // 22-field case class — the classic Scala arity cap.
  private final case class Twenty2(
    a: Int,
    b: Int,
    c: Int,
    d: Int,
    e: Int,
    f: Int,
    g: Int,
    h: Int,
    i: Int,
    j: Int,
    k: Int,
    l: Int,
    m: Int,
    n: Int,
    o: Int,
    p: Int,
    q: Int,
    r: Int,
    s: Int,
    t: Int,
    u: Int,
    v: Int,
  ) derives CsvRowEncoder

  private final case class Weird(`user-id`: Int, `日本語`: String) derives CsvRowEncoder

  private val arityHeadersSpec =
    suite("header derivation")(
      test("arity 1")(assertTrue(header[One] == IndexedSeq("a"))),
      test("arity 2")(assertTrue(header[Two] == IndexedSeq("a", "b"))),
      test("arity 5")(assertTrue(header[Five] == IndexedSeq("a", "b", "c", "d", "e"))),
      test("arity 10") {
        assertTrue(header[Ten] == IndexedSeq("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"))
      },
      test("arity 22") {
        val expected = IndexedSeq(
          "a",
          "b",
          "c",
          "d",
          "e",
          "f",
          "g",
          "h",
          "i",
          "j",
          "k",
          "l",
          "m",
          "n",
          "o",
          "p",
          "q",
          "r",
          "s",
          "t",
          "u",
          "v",
        )
        assertTrue(header[Twenty2] == expected)
      },
      test("preserves backticked and unicode labels") {
        assertTrue(header[Weird] == IndexedSeq("user-id", "日本語"))
      },
    )

  private val encodeArityRoundTrip =
    suite("encode over arities")(
      test("arity 1 emits single field") {
        assertTrue(writeRow(One(42)) == "42\r\n")
      },
      test("arity 5 with Option") {
        assertTrue(
          writeRow(Five(1, "x", true, 99L, Some("y"))) == "1,x,true,99,y\r\n",
          writeRow(Five(1, "x", false, 99L, None)) == "1,x,false,99,\r\n",
        )
      },
      test("arity 22 fills in order") {
        val v   = Twenty2(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22)
        val out = writeRow(v)
        assertTrue(out == "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22\r\n")
      },
      test("weird labels emit correct header (label with '-' is quoted in header? no — no escape chars)") {
        val sw = new StringWriter
        val w  = CsvWriter.unsafeFromWriter(sw, CsvConfig.default)
        try w.writeHeader[Weird]()
        finally w.close()
        assertTrue(sw.toString == "user-id,日本語\r\n")
      },
    )

  // Codec path should be byte-identical to hand-written escape-hatch.
  private val codecMatchesHandWritten =
    suite("codec path matches escape hatch")(
      test("Five identical output through both paths") {
        val v   = Five(7, "a,b", true, 123L, Some("q\"r"))
        val sw1 = new StringWriter
        val w1  = CsvWriter.unsafeFromWriter(sw1, CsvConfig.default)
        try w1.writeRow(v)
        finally w1.close()
        val sw2 = new StringWriter
        val w2  = CsvWriter.unsafeFromWriter(sw2, CsvConfig.default)
        try
          w2.writeRow { e =>
            e.emitInt(v.a)
            e.emitString(v.b)
            e.emitBoolean(v.c)
            e.emitLong(v.d)
            v.e match {
              case Some(s) => e.emitString(s)
              case None    => e.emitEmpty()
            }
          }
        finally w2.close()
        assertTrue(sw1.toString == sw2.toString)
      }
    )

  // Compile-time failure tests via scala.compiletime.testing. We pin an error-message
  // substring so that unrelated compile failures (typos in the snippet, renames in the
  // library) don't accidentally turn these green.
  private val negativeCompile =
    suite("derivation fails cleanly for unsupported shapes")(
      test("missing CsvFieldEncoder for a field type") {
        // Define the unknown field type inside the quoted snippet so the outer
        // compiler doesn't see an unused local definition.
        val err      = scala.compiletime.testing.typeCheckErrors(
          """
          final class UnknownFieldType(val x: Int)
          final case class BadRow(c: UnknownFieldType) derives CsvRowEncoder
          """
        )
        val mentions = err.exists(_.message.contains("CsvFieldEncoder"))
        assertTrue(err.nonEmpty, mentions)
      },
      test("non-product type (no Mirror.ProductOf)") {
        val err      = scala.compiletime.testing.typeCheckErrors(
          """
          CsvRowEncoder.derived[List[Int]]
          """
        )
        val mentions = err.exists(e => e.message.contains("Product") || e.message.contains("Mirror"))
        assertTrue(err.nonEmpty, mentions)
      },
    )

  // Hand-built encoder via the .custom builder. Defined outside the suite so the
  // anonymous-given form works at the case-class call sites below.
  private final case class User(
    id: Long,
    email: String,
    passwordHash: String,
    name: String,
  )
  private given userPartialEncoder: CsvRowEncoder[User] =
    CsvRowEncoder.custom(IndexedSeq("id", "name", "email")) { (u, out) =>
      out.emitLong(u.id)
      out.emitString(u.name)
      out.emitString(u.email)
    }

  private val customSpec =
    suite(".custom")(
      test("uses the supplied header list verbatim") {
        assertTrue(header[User] == IndexedSeq("id", "name", "email"))
      },
      test("encodes only the fields the lambda emits, in the lambda's order") {
        val u = User(42L, "ada@example.com", "secret", "Ada")
        assertTrue(writeRow(u) == "42,Ada,ada@example.com\r\n")
      },
      test("escaping still goes through the FieldEmitter") {
        val u = User(1L, "a,b@example.com", "x", "he said \"hi\"")
        assertTrue(writeRow(u) == "1,\"he said \"\"hi\"\"\",\"a,b@example.com\"\r\n")
      },
      test("writeHeader[A]() emits the supplied header row") {
        val sw = new StringWriter
        val w  = CsvWriter.unsafeFromWriter(sw, CsvConfig.default)
        try w.writeHeader[User]()
        finally w.close()
        assertTrue(sw.toString == "id,name,email\r\n")
      },
    )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("CsvRowEncoder")(arityHeadersSpec, encodeArityRoundTrip, codecMatchesHandWritten, customSpec, negativeCompile)
}
