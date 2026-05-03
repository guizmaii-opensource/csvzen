import BuildHelper.{noDoc, stdSettings}

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalaVersion      := "3.3.7"
ThisBuild / scalafmtCheck     := true
ThisBuild / scalafmtSbtCheck  := true
ThisBuild / scalafmtOnCompile := !insideCI.value
ThisBuild / scalafixOnCompile := !insideCI.value
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision // use Scalafix compatible version

// ### Aliases ###

addCommandAlias("tc", "Test/compile")
addCommandAlias("ctc", "clean; tc")
addCommandAlias("rctc", "reload; ctc")
addCommandAlias("fix", "scalafixAll; scalafmtAll; scalafmtSbt")
addCommandAlias("check", "scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck")

// ### Dependencies ###

lazy val zioVersion = "2.1.25"

// ### Modules ###

lazy val root =
  Project(id = "csvzen", base = file("."))
    .settings(noDoc *)
    .settings(publish / skip := true)
    .settings(crossScalaVersions := Nil) // https://www.scala-sbt.org/1.x/docs/Cross-Build.html#Cross+building+a+project+statefully,
    .aggregate(core, `test-kit`, zio, bench)

lazy val core =
  project
    .in(file("modules/core"))
    .settings(stdSettings *)
    .settings(
      name := "csvzen-core",
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio-test"     % zioVersion % Test,
        "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      ),
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    )

lazy val `test-kit` =
  project
    .in(file("modules/test-kit"))
    .dependsOn(core)
    .settings(stdSettings *)
    .settings(
      name := "csvzen-test-kit",
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio"               % zioVersion,
        "dev.zio" %% "zio-test"          % zioVersion,
        "dev.zio" %% "zio-test-magnolia" % zioVersion,
        "dev.zio" %% "zio-test-sbt"      % zioVersion % Test,
      ),
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    )

lazy val zio =
  project
    .in(file("modules/zio"))
    .dependsOn(core)
    .settings(stdSettings *)
    .settings(
      name := "csvzen-zio",
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio"          % zioVersion,
        "dev.zio" %% "zio-streams"  % zioVersion,
        "dev.zio" %% "zio-test"     % zioVersion % Test,
        "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      ),
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    )

lazy val bench =
  project
    .in(file("modules/bench"))
    .dependsOn(core)
    .enablePlugins(JmhPlugin)
    .settings(stdSettings *)
    .settings(
      name           := "csvzen-bench",
      publish / skip := true,
      // Bench JVM is JDK 25 (per design doc Q5). The library itself stays on
      // javaTarget 17 until the JDK floor is bumped in P3/P5; the bench module
      // overrides via -release so it can use newer JDK features in the harness.
      javacOptions   := Seq("-source", "21", "-target", "21"),
      scalacOptions  := scalacOptions.value.filterNot(_.startsWith("-release")) :+ "-release:21",
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio-test"          % zioVersion % Test,
        "dev.zio" %% "zio-test-sbt"      % zioVersion % Test,
        "dev.zio" %% "zio-test-magnolia" % zioVersion,
      ),
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    )

inThisBuild(
  List(
    organization := "com.guizmaii",
    homepage     := Some(url("https://github.com/guizmaii-opensource/csvzen")),
    licenses     := List("Apache 2.0" -> url("https://opensource.org/license/apache-2.0")),
    developers   := List(
      Developer(
        "guizmaii",
        "Jules Ivanic",
        "jules.ivanic@gmail.com",
        url("https://blog.jules-ivanic.com/#/")
      )
    )
  )
)
