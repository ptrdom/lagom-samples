import com.lightbend.lagom.core.LagomVersion.{ current => lagomVersion }

organization in ThisBuild := "com.example"

// the Scala version that will be used for cross-compiled libraries
scalaVersion in ThisBuild := "2.13.5"

val postgresDriver             = "org.postgresql"                % "postgresql"                                    % "42.2.18"
val macwire                    = "com.softwaremill.macwire"     %% "macros"                                        % "2.3.7" % "provided"
val scalaTest                  = "org.scalatest"                %% "scalatest"                                     % "3.2.2" % Test
val akkaDiscoveryKubernetesApi = "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api"                 % "1.0.10"
val lagomScaladslAkkaDiscovery = "com.lightbend.lagom"          %% "lagom-scaladsl-akka-discovery-service-locator" % lagomVersion

ThisBuild / scalacOptions ++= List("-encoding", "utf8", "-deprecation", "-feature", "-unchecked", "-Xfatal-warnings")

def dockerSettings = Seq(
  dockerUpdateLatest := true,
  dockerBaseImage := getDockerBaseImage(),
  dockerUsername := sys.props.get("docker.username"),
  dockerRepository := sys.props.get("docker.registry")
)

val akkaVersion                = "2.6.13"
val akkaPersistenceJdbcVersion = "5.0.0"
val akkaProjectionVersion      = "1.2.1"

def akkaDependencies = Seq(
  libraryDependencies ++= Seq(
    "com.typesafe.akka"  %% "akka-actor"                   % akkaVersion,
    "com.typesafe.akka"  %% "akka-remote"                  % akkaVersion,
    "com.typesafe.akka"  %% "akka-cluster"                 % akkaVersion,
    "com.typesafe.akka"  %% "akka-cluster-sharding"        % akkaVersion,
    "com.typesafe.akka"  %% "akka-cluster-sharding-typed"  % akkaVersion,
    "com.typesafe.akka"  %% "akka-cluster-tools"           % akkaVersion,
    "com.typesafe.akka"  %% "akka-cluster-typed"           % akkaVersion,
    "com.typesafe.akka"  %% "akka-coordination"            % akkaVersion,
    "com.typesafe.akka"  %% "akka-discovery"               % akkaVersion,
    "com.typesafe.akka"  %% "akka-distributed-data"        % akkaVersion,
    "com.typesafe.akka"  %% "akka-serialization-jackson"   % akkaVersion,
    "com.typesafe.akka"  %% "akka-persistence"             % akkaVersion,
    "com.typesafe.akka"  %% "akka-persistence-typed"       % akkaVersion,
    "com.typesafe.akka"  %% "akka-persistence-query"       % akkaVersion,
    "com.lightbend.akka" %% "akka-persistence-jdbc"        % akkaPersistenceJdbcVersion,
    "com.typesafe.akka"  %% "akka-slf4j"                   % akkaVersion,
    "com.typesafe.akka"  %% "akka-stream"                  % akkaVersion,
    "com.typesafe.akka"  %% "akka-protobuf-v3"             % akkaVersion,
    "com.typesafe.akka"  %% "akka-actor-typed"             % akkaVersion,
    "com.lightbend.akka" %% "akka-projection-eventsourced" % akkaProjectionVersion,
    "com.lightbend.akka" %% "akka-projection-slick"        % akkaProjectionVersion,
    "com.lightbend.akka" %% "akka-projection-kafka"        % akkaProjectionVersion,
    "com.typesafe.akka"  %% "akka-multi-node-testkit"      % akkaVersion % Test,
    "com.typesafe.akka"  %% "akka-testkit"                 % akkaVersion % Test,
    "com.typesafe.akka"  %% "akka-stream-testkit"          % akkaVersion % Test,
    "com.typesafe.akka"  %% "akka-actor-testkit-typed"     % akkaVersion % Test
  ),
)

def getDockerBaseImage(): String = sys.props.get("java.version") match {
  case Some(v) if v.startsWith("11") => "adoptopenjdk/openjdk11"
  case _                             => "adoptopenjdk/openjdk8"
}

// Update the version generated by sbt-dynver to remove any + characters, since these are illegal in docker tags
version in ThisBuild ~= (_.replace('+', '-'))
dynver in ThisBuild ~= (_.replace('+', '-'))

lazy val `shopping-cart-scala` = (project in file("."))
  .aggregate(`shopping-cart-api`, `shopping-cart`, `inventory-api`, inventory, utility)

lazy val `shopping-cart-api` = (project in file("shopping-cart-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi
    )
  )

lazy val `shopping-cart` = (project in file("shopping-cart"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslCluster,
      lagomScaladslTestKit,
      macwire,
      scalaTest,
      postgresDriver,
      lagomScaladslAkkaDiscovery,
      akkaDiscoveryKubernetesApi,
      "com.typesafe.akka" %% "akka-persistence-testkit" % "2.6.14" % Test
    )
  )
  .settings(dockerSettings)
  .settings(akkaDependencies)
  .settings(lagomForkedTestSettings)
  .dependsOn(`shopping-cart-api`, utility)

lazy val `inventory-api` = (project in file("inventory-api"))
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslApi
    )
  )

lazy val inventory = (project in file("inventory"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslCluster,
      lagomScaladslTestKit,
      macwire,
      scalaTest,
      lagomScaladslAkkaDiscovery
    )
  )
  .settings(dockerSettings)
  .settings(akkaDependencies)
  .dependsOn(`inventory-api`, `shopping-cart-api`, utility)

lazy val utility = (project in file("utility"))
  .enablePlugins(LagomScala)
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslCluster,
      lagomScaladslTestKit,
      macwire,
      scalaTest,
      lagomScaladslAkkaDiscovery
    )
  )
  .settings(dockerSettings)
  .settings(akkaDependencies)

// The project uses PostgreSQL
lagomCassandraEnabled in ThisBuild := false

// Use Kafka server running in a docker container
lagomKafkaEnabled in ThisBuild := false
lagomKafkaPort in ThisBuild := 9092
