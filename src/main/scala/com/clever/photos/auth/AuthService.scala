package com.clever.photos.auth

import com.clever.photos.config.AuthConfig
import com.clever.photos.domain.*
import com.clever.photos.repository.ApiClientRepository
import org.mindrot.jbcrypt.BCrypt
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import zio.*
import zio.json.*

import java.time.Instant
import java.util.UUID
import scala.util.{Failure, Success}

// ─── Auth error type ─────────────────────────────────────────────────────────

enum AuthError:
  case InvalidCredentials(msg: String)
  case InvalidToken(msg: String)
  case InsufficientScope(required: String)
  case ClientNotFound(clientId: String)
  case ServiceUnavailable(msg: String)
  case RateLimitExceeded

// ─── Service trait ────────────────────────────────────────────────────────────

/** Authentication and authorisation contract.
  *
  * Two implementations exist:
  *  - [[LiveAuthService]]  — validates against the database, issues real JWTs.
  *  - [[MockAuthService]]  — accepts any non-empty token, returns all scopes.
  *    Switch via `AUTH_MOCK_MODE=true` in the environment.
  *
  * Injection pattern: wire one of the two implementations via ZLayer in Main.
  * Tests can provide a ZLayer[Any, Nothing, AuthService] that uses
  * MockAuthService, requiring no database.
  */
trait AuthService:
  /** Validate client_id + client_secret and return a signed JWT. */
  def issueToken(clientId: String, clientSecret: String): IO[AuthError, TokenResponse]

  /** Verify a Bearer JWT and extract the caller's identity and scopes. */
  def validateToken(token: String): IO[AuthError, TokenClaims]

  /** Require that the claims include a specific scope; fail with
    * [[AuthError.InsufficientScope]] otherwise.
    */
  def requireScope(claims: TokenClaims, scope: String): IO[AuthError, Unit] =
    if claims.hasScope(scope) then ZIO.unit
    else ZIO.fail(AuthError.InsufficientScope(scope))

object AuthService:
  def issueToken(clientId: String, clientSecret: String): ZIO[AuthService, AuthError, TokenResponse] =
    ZIO.serviceWithZIO(_.issueToken(clientId, clientSecret))
  def validateToken(token: String): ZIO[AuthService, AuthError, TokenClaims] =
    ZIO.serviceWithZIO(_.validateToken(token))

// ─── JWT payload ─────────────────────────────────────────────────────────────

// jwt-scala-core 10.x strips registered claim names (sub, iss, aud, exp, iat, jti)
// from claim.content during decode and populates them as JwtClaim registered fields.
// claim.audience is also not reliably populated on decode (library limitation).
// Strategy:
//  - `sub` (clientId) is set as a registered JwtClaim field → read back via claim.subject
//  - `scopes` is a custom claim → stays in claim.content
//  - `issuer`/`audience` use non-standard JSON names (not "iss"/"aud") so they
//    remain in claim.content and can be validated directly after decode
private final case class JwtPayload(
  scopes:   List[String],
  issuer:   String,   // custom name (not "iss") → stays in claim.content after decode
  audience: String    // custom name (not "aud") → stays in claim.content after decode
)

private object JwtPayload:
  given JsonCodec[JwtPayload] = DeriveJsonCodec.gen

// ─── Live implementation ──────────────────────────────────────────────────────

/** Production auth service.
  *
  * Flow:
  *  1. Look up the client by client_id in the database.
  *  2. Verify the submitted secret against the stored BCrypt hash.
  *  3. Build a JWT whose payload encodes the client's scopes.
  *  4. On subsequent requests, verify the JWT signature and decode scopes.
  *
  * The JWT is HMAC-SHA256 signed using the configured `jwtSecret`.
  * Tokens expire after `tokenExpirySeconds` (default 900 seconds / 15 minutes).
  *
  * Rate limiting: at most 5 failed attempts per client_id in a 5-minute window.
  * NOTE: This is an in-memory rate limiter — it does not persist across restarts
  * and does not coordinate across multiple instances. A Redis-backed solution
  * is required for multi-instance deployments.
  */
final class LiveAuthService(
  clientRepo: ApiClientRepository,
  config:     AuthConfig,
  failureLog: Ref[Map[String, List[Instant]]]
) extends AuthService:

  private val algorithm    = JwtAlgorithm.HS256
  private val windowSecs   = 300L  // 5-minute rate-limit window
  private val maxAttempts  = 5

  def issueToken(clientId: String, clientSecret: String): IO[AuthError, TokenResponse] =
    for
      now          <- ZIO.succeed(Instant.now())
      windowStart   = now.minusSeconds(windowSecs)
      log          <- failureLog.get
      recentFails   = log.getOrElse(clientId, List.empty).count(_.isAfter(windowStart))
      _            <- ZIO.when(recentFails >= maxAttempts)(ZIO.fail(AuthError.RateLimitExceeded))
      result       <- clientRepo
                        .findById(clientId)
                        .mapError(e => AuthError.ServiceUnavailable(e.getMessage))
                        .flatMap {
                          case None =>
                            ZIO.fail(AuthError.InvalidCredentials("Invalid client_id or client_secret"))
                          case Some(client) if !BCrypt.checkpw(clientSecret, client.secretHash) =>
                            ZIO.fail(AuthError.InvalidCredentials("Invalid client_id or client_secret"))
                          case Some(client) =>
                            ZIO.attempt(buildToken(client))
                              .mapError(e => AuthError.ServiceUnavailable(e.getMessage))
                        }
                        .tapError {
                          case AuthError.InvalidCredentials(_) | AuthError.ClientNotFound(_) =>
                            failureLog.update { m =>
                              m.updated(clientId, Instant.now() :: m.getOrElse(clientId, List.empty))
                            }
                          case _ => ZIO.unit
                        }
                        .tap(_ => failureLog.update(_ - clientId))
    yield result

  def validateToken(token: String): IO[AuthError, TokenClaims] =
    Jwt.decode(token, config.jwtSecret, Seq(algorithm)) match
      case Failure(_) =>
        ZIO.fail(AuthError.InvalidToken("Token is invalid or expired"))
      case Success(claim) =>
        // scopes, issuer, audience stay in claim.content (non-standard field names).
        // clientId is recovered from claim.subject (registered field, decoded reliably).
        claim.content.fromJson[JwtPayload] match
          case Left(err) =>
            ZIO.fail(AuthError.InvalidToken(s"Malformed token payload: $err"))
          case Right(payload) if payload.issuer != "clever-photos-api" =>
            ZIO.fail(AuthError.InvalidToken("Invalid token issuer"))
          case Right(payload) if payload.audience != "clever-photos-api" =>
            ZIO.fail(AuthError.InvalidToken("Invalid token audience"))
          case Right(payload) =>
            claim.subject match
              case None      => ZIO.fail(AuthError.InvalidToken("Malformed token: missing subject"))
              case Some(sub) => ZIO.succeed(TokenClaims(clientId = sub, scopes = payload.scopes))

  private def buildToken(client: ApiClient): TokenResponse =
    val now     = Instant.now().getEpochSecond
    val exp     = now + config.tokenExpirySeconds
    val jti     = UUID.randomUUID().toString
    // scopes/issuer/audience go in content under non-standard names so they
    // survive the jwt-scala-core decode round-trip unchanged (jwt-scala strips
    // the registered claim names "sub"/"iss"/"aud" from content on decode).
    // clientId is stored as the registered "sub" claim for RFC 7519 compliance.
    val payload = JwtPayload(
      scopes   = client.scopes,
      issuer   = "clever-photos-api",
      audience = "clever-photos-api"
    ).toJson

    val claim = JwtClaim(
      content    = payload,
      subject    = Some(client.clientId),
      issuedAt   = Some(now),
      expiration = Some(exp),
      jwtId      = Some(jti)
    )

    val token = Jwt.encode(claim, config.jwtSecret, algorithm)
    TokenResponse(
      accessToken = token,
      tokenType   = "Bearer",
      expiresIn   = config.tokenExpirySeconds,
      scope       = client.scopes.mkString(" ")
    )

object LiveAuthService:
  val layer: URLayer[ApiClientRepository & AuthConfig, AuthService] =
    ZLayer {
      for
        clientRepo <- ZIO.service[ApiClientRepository]
        config     <- ZIO.service[AuthConfig]
        failureLog <- Ref.make(Map.empty[String, List[Instant]])
      yield new LiveAuthService(clientRepo, config, failureLog)
    }

// ─── Mock implementation ──────────────────────────────────────────────────────

/** Development / test stub. Accepts any non-empty Bearer token and returns
  * full admin-level scopes. Enabled via `AUTH_MOCK_MODE=true`.
  *
  * NEVER use in production.
  */
final class MockAuthService extends AuthService:

  def issueToken(clientId: String, clientSecret: String): IO[AuthError, TokenResponse] =
    ZIO.succeed(
      TokenResponse(
        accessToken = s"mock-token-for-$clientId",
        tokenType   = "Bearer",
        expiresIn   = 900L,
        scope       = Scopes.all.mkString(" ")
      )
    )

  def validateToken(token: String): IO[AuthError, TokenClaims] =
    if token.isBlank then ZIO.fail(AuthError.InvalidToken("Token must not be empty"))
    else ZIO.succeed(TokenClaims(clientId = "mock-client", scopes = Scopes.all))

object MockAuthService:
  val layer: ULayer[AuthService] =
    ZLayer.succeed(new MockAuthService)
