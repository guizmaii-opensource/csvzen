package com.guizmaii.csvzen.testkit

import com.guizmaii.csvzen.core.CsvRowEncoder
import zio.Scope
import zio.test.*
import zio.test.magnolia.DeriveGen

object GoldenSpec extends ZIOSpecDefault {

  // The DeriveGen-driven generator depends on the case class's Mirror, so the type
  // must live outside the spec body for the macro to find the synthetic mirror.
  final case class SimpleRecord(id: Int, name: String, active: Boolean) derives CsvRowEncoder

  // A second type used to verify GoldenConfiguration.relativePath disambiguates
  // identically-named goldens. Same `SimpleRecord` shape, different relative path.
  private val nestedConfig: GoldenConfiguration =
    GoldenConfiguration.default.copy(relativePath = "nested")

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("GoldenSpec")(
      goldenTest(DeriveGen[SimpleRecord]), {
        given GoldenConfiguration = nestedConfig
        goldenTest(DeriveGen[SimpleRecord])
      },
    )
}
