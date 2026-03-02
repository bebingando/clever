package com.clever.photos

import com.clever.photos.auth.*
import com.clever.photos.config.AuthConfig
import com.clever.photos.domain.*
import com.clever.photos.repository.ApiClientRepository
import org.mindrot.jbcrypt.BCrypt
import pdi.jwt.{Jwt, JwtAlgorithm}
import zio.*
import zio.test.*
import zio.test.Assertion.*

/** Unit tests for authentication and JWT handling.
  *
  * These tests require NO database — the ApiClientRepository is replaced with
  * an in-memory stub, demonstrating the mockability of the auth layer.
  */
object AuthServiceSpec extends ZIOSpecDefault:

  private val testSecret  = "test-jwt-secret"
  private val testExpiry  = 3600L
  private val testConfig  = AuthConfig(testSecret, testExpiry, mockMode = false)

  // ─── In-memory ApiClientRepository stub ─────────────────────────────────

  private def makeStubRepo(clients: ApiClient*): ULayer[ApiClientRepository] =
    ZLayer.succeed {
      val store = clients.map(c => c.clientId -> c).toMap
      new ApiClientRepository:
        def findById(clientId: String): Task[Option[ApiClient]] =
          ZIO.succeed(store.get(clientId))
        def create(client: ApiClient): Task[ApiClient] =
          ZIO.succeed(client)
        def countAll(): Task[Long] =
          ZIO.succeed(store.size.toLong)
    }

  private val validClientId     = "test-client"
  private val validClientSecret = "super-secret-123"
  private val validSecretHash   = BCrypt.hashpw(validClientSecret, BCrypt.gensalt(4)) // fast rounds for tests

  private val validClient = ApiClient(
    clientId   = validClientId,
    secretHash = validSecretHash,
    name       = "Test Client",
    scopes     = List(Scopes.PhotosRead, Scopes.PhotosWrite),
    isActive   = true
  )

  private val authLayer: ULayer[AuthService] =
    ZLayer.make[AuthService](
      makeStubRepo(validClient),
      ZLayer.succeed(testConfig),
      LiveAuthService.layer
    )

  // ─── Tests ────────────────────────────────────────────────────────────────

  def spec = suite("AuthServiceSpec")(

    suite("issueToken")(
      test("returns a TokenResponse for valid credentials") {
        AuthService
          .issueToken(validClientId, validClientSecret)
          .map { resp =>
            assertTrue(resp.tokenType == "Bearer") &&
            assertTrue(resp.expiresIn == testExpiry) &&
            assertTrue(resp.accessToken.nonEmpty) &&
            assertTrue(resp.scope.contains(Scopes.PhotosRead))
          }
          .provide(authLayer)
      },

      test("fails with InvalidCredentials for wrong secret") {
        AuthService
          .issueToken(validClientId, "wrong-password")
          .flip
          .map { err =>
            assertTrue(err.isInstanceOf[AuthError.InvalidCredentials])
          }
          .provide(authLayer)
      },

      test("fails with InvalidCredentials for unknown client_id") {
        AuthService
          .issueToken("no-such-client", "anything")
          .flip
          .map { err =>
            assertTrue(err.isInstanceOf[AuthError.InvalidCredentials])
          }
          .provide(authLayer)
      }
    ),

    suite("validateToken")(
      test("validates a token that was just issued") {
        for
          resp   <- AuthService.issueToken(validClientId, validClientSecret)
          claims <- AuthService.validateToken(resp.accessToken)
        yield
          assertTrue(claims.clientId == validClientId) &&
          assertTrue(claims.scopes.contains(Scopes.PhotosRead))
      }.provide(authLayer),

      test("fails for a tampered token") {
        for
          resp    <- AuthService.issueToken(validClientId, validClientSecret)
          bad      = resp.accessToken + "tampered"
          result  <- AuthService.validateToken(bad).flip
        yield assertTrue(result.isInstanceOf[AuthError.InvalidToken])
      }.provide(authLayer),

      test("fails for empty string") {
        AuthService
          .validateToken("")
          .flip
          .map(e => assertTrue(e.isInstanceOf[AuthError.InvalidToken]))
          .provide(authLayer)
      }
    ),

    suite("requireScope")(
      test("passes when the claim has the required scope") {
        val claims  = TokenClaims("c", List(Scopes.PhotosRead, Scopes.PhotosWrite))
        val service = new MockAuthService
        service.requireScope(claims, Scopes.PhotosRead).as(assertTrue(true))
      },

      test("fails with InsufficientScope when the scope is missing") {
        val claims  = TokenClaims("c", List(Scopes.PhotosRead))
        val service = new MockAuthService
        service.requireScope(claims, Scopes.Admin).flip.map { e =>
          assertTrue(e.isInstanceOf[AuthError.InsufficientScope])
        }
      }
    ),

    suite("MockAuthService")(
      test("accepts any non-empty token and returns all scopes") {
        val mock = new MockAuthService
        mock.validateToken("any-token-value").map { claims =>
          assertTrue(claims.scopes == Scopes.all)
        }
      },

      test("rejects empty token") {
        val mock = new MockAuthService
        mock.validateToken("").flip.map { e =>
          assertTrue(e.isInstanceOf[AuthError.InvalidToken])
        }
      }
    )
  )
