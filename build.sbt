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
    .aggregate(core)

lazy val core =
  project
    .in(file("core"))
    .settings(stdSettings *)
    .settings(
      name := "csvzen-core",
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio-test"     % zioVersion % Test,
        "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
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
