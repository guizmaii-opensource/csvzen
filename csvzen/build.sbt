ThisBuild / scalaVersion := "3.3.7"

lazy val root = (project in file("."))
  .settings(
    name := "csvzen",
    idePackagePrefix := Some("com.guizmaii.csvzen")
  )
