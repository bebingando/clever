package com.clever.photos

import com.clever.photos.api.Server
import com.clever.photos.auth.{AuthService, LiveAuthService, MockAuthService}
import com.clever.photos.config.*
import com.clever.photos.db.Database
import com.clever.photos.domain.{ApiClient, Scopes}
import com.clever.photos.ingest.CsvIngester
import com.clever.photos.repository.*
import org.mindrot.jbcrypt.BCrypt
import zio.*
import zio.logging.backend.SLF4J

/** Application entry point.
  *
  * Startup sequence:
  *  1. Load configuration from application.conf + environment variables.
  *  2. Run Flyway migrations (idempotent).
  *  3. Create HikariCP connection pool.
  *  4. Seed a default API client if none exist (first-boot convenience).
  *  5. Ingest photos.csv if the photos table is empty.
  *  6. Start the ZIO-HTTP / Tapir server.
  */
object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    program.provide(
      AppConfig.layer,
      // Derive sub-configs from AppConfig
      ZLayer.fromFunction((c: AppConfig) => c.db),
      ZLayer.fromFunction((c: AppConfig) => c.http),
      ZLayer.fromFunction((c: AppConfig) => c.auth),
      ZLayer.fromFunction((c: AppConfig) => c.ingest),
      ZLayer.fromFunction((c: AppConfig) => c.defaultClient),
      // Database
      Database.layer,
      // Repositories
      LivePhotoRepository.layer,
      LivePhotographerRepository.layer,
      LiveApiClientRepository.layer,
      // Auth service — chosen at runtime based on AUTH_MOCK_MODE
      ZLayer.fromZIO(
        ZIO.serviceWith[AuthConfig](_.mockMode).flatMap { mock =>
          if mock then
            ZIO.logWarning("AUTH_MOCK_MODE=true — MockAuthService active. DO NOT USE IN PRODUCTION.").as(
              ZLayer.succeed[AuthService](new MockAuthService)
            )
          else
            ZIO.succeed(LiveAuthService.layer)
        }
      ).flatten,
      Scope.default
    )

  private val program =
    for
      appCfg <- ZIO.service[AppConfig]

      // 1. Run Flyway migrations
      _ <- ZIO.logInfo("Running database migrations...")
      _ <- Database.runMigrations(appCfg.db)
      _ <- ZIO.logInfo("Migrations complete")

      // 2. Seed default API client on first boot
      _ <- seedDefaultClient(appCfg.defaultClient, appCfg.auth.jwtSecret)

      // 3. Ingest CSV data if enabled and table is empty
      _ <- ZIO.when(appCfg.ingest.enabled)(
             ZIO.logInfo("Checking for CSV ingest...") *>
             CsvIngester.ingest
           )

      // 4. Start HTTP server
      _ <- ZIO.logInfo(s"Starting server on ${appCfg.http.host}:${appCfg.http.port}")
      _ <- Server.start(appCfg.http)
    yield ()

  /** Creates a default API client with full scopes if no clients exist yet.
    * This allows first-boot usage without any manual DB setup.
    */
  private def seedDefaultClient(
    cfg:    DefaultClientConfig,
    secret: String
  ): ZIO[ApiClientRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[ApiClientRepository] { repo =>
      repo.countAll().flatMap { count =>
        if count > 0 then ZIO.logInfo("API clients already seeded, skipping")
        else
          val hash = BCrypt.hashpw(cfg.clientSecret, BCrypt.gensalt(12))
          val client = ApiClient(
            clientId   = cfg.clientId,
            secretHash = hash,
            name       = "Default Development Client",
            scopes     = Scopes.all,
            isActive   = true
          )
          repo.create(client) *>
            ZIO.logInfo(
              s"Created default API client '${cfg.clientId}'. " +
              "Change DEFAULT_CLIENT_SECRET in production!"
            )
      }
    }
