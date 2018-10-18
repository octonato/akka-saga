name := "akka-saga"

version := "0.1.0"

scalaVersion := "2.12.6"

lazy val akkaVersion = "2.5.17"

lazy val httpVersion = "10.1.5"

enablePlugins(SbtReactiveAppPlugin, Cinnamon)

scalacOptions := Seq("-Ywarn-unused", "-Ywarn-dead-code", "-Ywarn-unused-import")

libraryDependencies ++= Seq(
  "com.typesafe.akka"         %% "akka-actor"                % akkaVersion,
  "com.typesafe.akka"         %% "akka-persistence"          % akkaVersion,
  "com.typesafe.akka"         %% "akka-cluster-sharding"     % akkaVersion,
  "com.typesafe.akka"         %% "akka-http"                 % httpVersion,
  "com.typesafe.akka"         %% "akka-http-spray-json"      % httpVersion,
  "com.typesafe.akka"         %% "akka-slf4j"                % akkaVersion,
  "com.typesafe.akka"         %% "akka-persistence-query"    % akkaVersion,
  "com.typesafe.akka"         %% "akka-testkit"              % akkaVersion  % "test",
  "com.typesafe.akka"         %% "akka-http-testkit"         % httpVersion  % "test",
  "org.iq80.leveldb"           % "leveldb"                   % "0.10"       % "test",
  "org.fusesource.leveldbjni"  % "leveldbjni-all"            % "1.8"        % "test",
  "org.scalatest"             %% "scalatest"                 % "3.0.5"      % "test"
)

// Add Cinnamon library dependencies.
libraryDependencies ++= Vector(
  Cinnamon.library.cinnamonCHMetrics,
  Cinnamon.library.cinnamonAkka,
  Cinnamon.library.cinnamonAkkaHttp,
  Cinnamon.library.cinnamonJvmMetricsProducer,
  Cinnamon.library.cinnamonPrometheus,
  Cinnamon.library.cinnamonPrometheusHttpServer
)

mainClass in Compile := Some("com.example.AkkaSagaApp")

enableAkkaClusterBootstrap := true

endpoints += TcpEndpoint("cinnamon", 9091, None)
endpoints += HttpEndpoint("http", 8080, HttpIngress(Seq(80), Seq("akka-saga.io"), Seq("/")))

annotations := Map(
  // enable scraping
  "prometheus.io/scrape" -> "true",
  "prometheus.io/port" -> "9091"
)

fork := true
