name := "akka-quickstart-scala"

version := "1.0"

scalaVersion := "2.12.6"

lazy val akkaVersion = "2.5.13"

enablePlugins (Cinnamon)

scalacOptions := Seq("-Ywarn-unused", "-Ywarn-dead-code", "-Ywarn-unused-import")

// Add the Cinnamon Agent for run and test
cinnamon in run := true
cinnamon in test := true

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor"            % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit"          % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence"      % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
  Cinnamon.library.cinnamonAkka,
  Cinnamon.library.cinnamonAkkaHttp,
  Cinnamon.library.cinnamonPrometheusHttpServer,
  Cinnamon.library.cinnamonJvmMetricsProducer,
  "org.scalatest"     %% "scalatest"             % "3.0.5"      % "test"
)
