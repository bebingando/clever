val scala3Version = "3.4.2"

val zioVersion            = "2.1.9"
val zioHttpVersion        = "3.0.1"
val zioJsonVersion        = "0.7.3"
val zioConfigVersion      = "4.0.2"
val zioInteropCatsVersion = "23.1.0.2"
val zioLoggingVersion     = "2.3.1"
val tapirVersion          = "1.11.1"
val doobieVersion         = "1.0.0-RC4"
val flywayVersion         = "10.17.3"
val jwtScalaVersion       = "10.0.1"
val bcryptVersion         = "0.4"
val logbackVersion        = "1.5.6"

lazy val root = project
  .in(file("."))
  .settings(
    name         := "clever-photos-api",
    version      := "0.1.0",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      // ZIO core
      "dev.zio" %% "zio"         % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      // ZIO-HTTP (Tapir server backend)
      "dev.zio" %% "zio-http" % zioHttpVersion,
      // ZIO-JSON
      "dev.zio" %% "zio-json" % zioJsonVersion,
      // ZIO Config (HOCON, auto-derivation)
      "dev.zio" %% "zio-config"           % zioConfigVersion,
      "dev.zio" %% "zio-config-typesafe"  % zioConfigVersion,
      "dev.zio" %% "zio-config-magnolia"  % zioConfigVersion,
      // ZIO Logging
      "dev.zio" %% "zio-logging"         % zioLoggingVersion,
      "dev.zio" %% "zio-logging-slf4j2"  % zioLoggingVersion,
      // Tapir: ZIO-HTTP server, ZIO-JSON codec, Swagger UI bundle
      "com.softwaremill.sttp.tapir" %% "tapir-zio-http-server"  % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-zio"          % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % tapirVersion,
      // Doobie: core, HikariCP pool, PostgreSQL dialect
      "org.tpolecat" %% "doobie-core"     % doobieVersion,
      "org.tpolecat" %% "doobie-hikari"   % doobieVersion,
      "org.tpolecat" %% "doobie-postgres" % doobieVersion,
      // ZIO ↔ Cats-Effect interop (required for Doobie)
      "dev.zio" %% "zio-interop-cats" % zioInteropCatsVersion,
      // Flyway database migrations (PostgreSQL module required in Flyway 10+)
      "org.flywaydb" % "flyway-core"               % flywayVersion,
      "org.flywaydb" % "flyway-database-postgresql" % flywayVersion,
      // JWT (jwt-scala core – no JSON-library dependency)
      "com.github.jwt-scala" %% "jwt-core" % jwtScalaVersion,
      // BCrypt for hashing client secrets
      "org.mindrot" % "jbcrypt" % bcryptVersion,
      // Logback (SLF4J implementation)
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      // Test
      "dev.zio" %% "zio-test"          % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt"      % zioVersion % Test,
      "dev.zio" %% "zio-test-magnolia" % zioVersion % Test,
      // Tapir stub server for endpoint-pipeline tests (no real HTTP binding)
      "com.softwaremill.sttp.tapir"   %% "tapir-sttp-stub-server" % tapirVersion % Test,
      "com.softwaremill.sttp.client3" %% "core"                   % "3.9.7"      % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    // Fat-JAR assembly settings
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*) => MergeStrategy.concat
      case PathList("META-INF", "MANIFEST.MF")  => MergeStrategy.discard
      case PathList("META-INF", _*)             => MergeStrategy.discard
      case PathList("module-info.class")         => MergeStrategy.discard
      case "reference.conf"                      => MergeStrategy.concat
      case "application.conf"                    => MergeStrategy.concat
      case _                                     => MergeStrategy.first
    },
    assembly / mainClass := Some("com.clever.photos.Main"),
  )
