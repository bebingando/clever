package com.clever.photos.config

import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*

/** HTTP server binding configuration. */
final case class HttpConfig(
  host: String,
  port: Int
)

/** PostgreSQL connection pool configuration. */
final case class DbConfig(
  url:      String,
  user:     String,
  password: String,
  poolSize: Int
)

/** JWT and auth-mode configuration. */
final case class AuthConfig(
  jwtSecret:           String,
  tokenExpirySeconds:  Long,
  /** When true, the MockAuthService is used: any non-empty Bearer token is
    * accepted and granted all scopes.  For development and unit-test
    * environments ONLY.
    */
  mockMode:            Boolean
)

/** CSV data-ingestion settings. */
final case class IngestConfig(
  /** Path to photos.csv; use "classpath:photos.csv" to read from the JAR. */
  csvPath:  String,
  /** When true, ingest runs at startup if the photos table is empty. */
  enabled:  Boolean
)

/** Credentials for the default API client created on first boot.
  * Injected via environment variables in docker-compose; never hardcode.
  */
final case class DefaultClientConfig(
  clientId:     String,
  clientSecret: String
)

/** Root application configuration, loaded from application.conf (HOCON)
  * with environment-variable overrides.
  */
final case class AppConfig(
  http:          HttpConfig,
  db:            DbConfig,
  auth:          AuthConfig,
  ingest:        IngestConfig,
  defaultClient: DefaultClientConfig
)

object AppConfig:
  private val descriptor: Config[AppConfig] = deriveConfig[AppConfig]

  val layer: ZLayer[Any, Config.Error, AppConfig] =
    ZLayer.fromZIO(ZIO.config(descriptor))
