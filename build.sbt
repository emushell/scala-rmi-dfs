name := "scala-rmi-dfs"

version := "0.1"

scalaVersion := "2.13.6"


lazy val rootProject = project
  .in(file("."))
  .settings(
    name := "Scala-RMI-DFS",
  ).aggregate(
  master,
  node,
  client
)

lazy val master = project
  .in(file("master"))
  .settings(
    commonSettings,
    name := "master",
    assembly / mainClass := Some("MasterNode"),
    libraryDependencies ++= Seq(dependencies.logging, dependencies.logback)
  )

lazy val node = project
  .in(file("node"))
  .settings(
    commonSettings,
    name := "node",
    assembly / mainClass := Some("DataNode"),
    libraryDependencies ++= Seq(dependencies.logging, dependencies.logback)
  ).dependsOn(
    master
  )


lazy val client = project
  .in(file("client"))
  .settings(
    commonSettings,
    name := "client",
    assembly / mainClass := Some("Client"),
    libraryDependencies ++= Seq(dependencies.logging, dependencies.logback)
  ).dependsOn(
  master,
  node
)

lazy val commonSettings = Seq(
  scalaVersion := "2.13.6",
  assemblyPackageScala / assembleArtifact := false,
  assembly / assemblyJarName := s"${name.value}_${scalaVersion.value}-${version.value}.jar",
)

lazy val dependencies = new {
  private val loggingV = "3.9.3"
  private val logbackV = "1.2.3"

  val logback = "ch.qos.logback" % "logback-classic" % logbackV
  val logging = "com.typesafe.scala-logging" %% "scala-logging" % loggingV
}