package com.clever.photos.db

import com.clever.photos.config.DbConfig
import doobie.hikari.HikariTransactor
import doobie.util.transactor.Transactor
import org.flywaydb.core.Flyway
import zio.*
import zio.interop.catz.*

import scala.concurrent.ExecutionContext

object Database:

  /** Creates a scoped HikariCP connection pool.
    *
    * The pool is closed automatically when the ZIO Scope ends (e.g., when the
    * application shuts down). Pool exhaustion surfaces to callers as a
    * [[doobie.util.invariant.InvariantViolation]] which the API layer maps to
    * HTTP 503.
    */
  def makeTransactor(config: DbConfig): ZIO[Scope, Throwable, Transactor[Task]] =
    HikariTransactor
      .newHikariTransactor[Task](
        driverClassName = "org.postgresql.Driver",
        url             = config.url,
        user            = config.user,
        pass            = config.password,
        connectEC       = ExecutionContext.global
      )
      .toScopedZIO

  /** ZLayer that wires DbConfig → Transactor[Task].
    * Scoped so the pool lives exactly as long as the application scope.
    */
  val layer: ZLayer[DbConfig, Throwable, Transactor[Task]] =
    ZLayer.scoped {
      ZIO.serviceWithZIO[DbConfig](makeTransactor)
    }

  /** Runs Flyway migrations against the configured database.
    * Idempotent: already-applied migrations are skipped.
    * Called once at application startup before any traffic is served.
    */
  def runMigrations(config: DbConfig): Task[Unit] =
    ZIO.attempt {
      Flyway
        .configure()
        .dataSource(config.url, config.user, config.password)
        .locations("classpath:db/migration")
        .load()
        .migrate()
    }.unit
