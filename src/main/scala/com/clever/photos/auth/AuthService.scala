package com.clever.photos.auth

import com.clever.photos.config.AuthConfig
import com.clever.photos.domain.*
import com.clever.photos.repository.ApiClientRepository
import org.mindrot.jbcrypt.BCrypt
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim}
import zio.*
import zio.json.*

import java.time.Instant
import scala.util.{Failure, Success}

// ─── Auth error type ─────────────────────────────────────────────────────────

enum AuthError:
  case InvalidCredentials(msg: String)
  case InvalidToken(msg: String)
  case InsufficientScope(required: String)
  case ClientNotFound(clientId: String)

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

private final case class JwtPayload(sub: String, scopes: List[String])

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
  * Tokens expire after `tokenExpirySeconds` (default 3600).
  */
final class LiveAuthService(
  clientRepo: ApiClientRepository,
  config:     AuthConfig
) extends AuthService:

  private val algorithm = JwtAlgorithm.HS256

  def issueToken(clientId: String, clientSecret: String): IO[AuthError, TokenResponse] =
    clientRepo
      .findById(clientId)
      .orDie
      .flatMap {
        case None =>
          ZIO.fail(AuthError.InvalidCredentials("Invalid client_id or client_secret"))
        case Some(client) if !BCrypt.checkpw(clientSecret, client.secretHash) =>
          ZIO.fail(AuthError.InvalidCredentials("Invalid client_id or client_secret"))
        case Some(client) =>
          ZIO.attempt(buildToken(client)).mapError(e => AuthError.InvalidCredentials(e.getMessage))
      }

  def validateToken(token: String): IO[AuthError, TokenClaims] =
    Jwt.decode(token, config.jwtSecret, Seq(algorithm)) match
      case Failure(_) =>
        ZIO.fail(AuthError.InvalidToken("Token is invalid or expired"))
      case Success(claim) =>
        claim.content.fromJson[JwtPayload] match
          case Left(err) =>
            ZIO.fail(AuthError.InvalidToken(s"Malformed token payload: $err"))
          case Right(payload) =>
            ZIO.succeed(TokenClaims(clientId = payload.sub, scopes = payload.scopes))

  private def buildToken(client: ApiClient): TokenResponse =
    val now     = Instant.now().getEpochSecond
    val exp     = now + config.tokenExpirySeconds
    val payload = JwtPayload(sub = client.clientId, scopes = client.scopes).toJson

    val claim = JwtClaim(
      content    = payload,
      subject    = Some(client.clientId),
      issuedAt   = Some(now),
      expiration = Some(exp)
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
    ZLayer.fromFunction(new LiveAuthService(_, _))

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
        expiresIn   = 3600L,
        scope       = Scopes.all.mkString(" ")
      )
    )

  def validateToken(token: String): IO[AuthError, TokenClaims] =
    if token.isBlank then ZIO.fail(AuthError.InvalidToken("Token must not be empty"))
    else ZIO.succeed(TokenClaims(clientId = "mock-client", scopes = Scopes.all))

object MockAuthService:
  val layer: ULayer[AuthService] =
    ZLayer.succeed(new MockAuthService)
