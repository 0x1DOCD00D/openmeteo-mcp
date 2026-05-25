ThisBuild / scalaVersion := "3.8.3"

ThisBuild / version := "0.1.0"
ThisBuild / organization := "Lone Star Consulting"
Test / fork := true

lazy val root = (project in file("."))
  .settings(
    name := "openmeteo-mcp",

    libraryDependencies += "com.lihaoyi" %% "ujson" % "4.4.3",
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.20" % Test,

    Compile / mainClass := Some("OpenMeteoMcpServer"),
    assembly / mainClass := Some("OpenMeteoMcpServer"),
    assembly / assemblyJarName := "openmeteo-mcp.jar"
  )