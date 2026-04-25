package com.guizmaii.csvzen.testkit

import com.guizmaii.csvzen.core.CsvConfig

/**
 * Configuration for [[csvGoldenTest]].
 *
 * @param relativePath
 *   path under `src/test/resources/golden/` where the golden file lives. Use this when
 *   you need to disambiguate identically-named types from different packages, or when
 *   you simply want to group golden files by feature.
 * @param sampleSize
 *   number of samples drawn from the supplied `Gen` and written into the golden file.
 * @param csvConfig
 *   dialect (delimiter, quote char, line terminator) used to encode the samples.
 *   Changing this changes the bytes on disk; pick once and stick with it.
 */
final case class GoldenConfiguration(
  relativePath: String = "",
  sampleSize: Int = 20,
  csvConfig: CsvConfig = CsvConfig.default,
)

object GoldenConfiguration {
  given default: GoldenConfiguration = GoldenConfiguration()
}
