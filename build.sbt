name := "akka-saga"

version := "0.1.0"

scalaVersion := "2.12.6"

lazy val akkaVersion = "2.5.16"

lazy val httpVersion = "10.1.5"

enablePlugins(SbtReactiveAppPlugin)

enableAkkaClusterBootstrap := true

scalacOptions := Seq("-Ywarn-unused", "-Ywarn-dead-code", "-Ywarn-unused-import")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor"            % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence"      % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
  "com.typesafe.akka" %% "akka-http"             % httpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json"  % httpVersion, 
  "com.typesafe.akka" %% "akka-slf4j"            % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit"          % akkaVersion  % "test",
  "org.scalatest"     %% "scalatest"             % "3.0.5"      % "test"
)

endpoints += HttpEndpoint("http", HttpIngress(Vector(80, 443), Vector.empty, Vector("/cluster-example")))

mainClass in Compile := Some("com.example.AkkaSagaApp")

applications += "akka-saga" -> Vector("bin/akka-saga")

deployMinikubeRpArguments ++= Vector(
  "--ingress-annotation", "ingress.kubernetes.io/rewrite-target=/",
  "--ingress-annotation", "nginx.ingress.kubernetes.io/rewrite-target=/"
)

deployMinikubeAkkaClusterBootstrapContactPoints := 3
