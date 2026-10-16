package com.guizmaii.csvzen.testkit

import zio.Scope
import zio.test.*

import java.io.File

object FilehelpersSpec extends ZIOSpecDefault {

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("filehelpers.resolveModuleRoot")(
      test("finds the module root from `target` when no override is given") {
        for {
          moduleRoot <- filehelpers.resolveModuleRoot(None)
        } yield assertTrue(moduleRoot.getName == "test-kit")
      },
      test("uses the override when given") {
        for {
          moduleRoot <- filehelpers.resolveModuleRoot(Some("/some/module"))
        } yield assertTrue(moduleRoot == new File("/some/module"))
      },
    )
}
