package com.clever.photos.api

import com.clever.photos.auth.AuthService
import com.clever.photos.domain.TokenResponse
import sttp.model.StatusCode
import sttp.tapir.{PublicEndpoint, AnyEndpoint}
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import sttp.tapir.ztapir.*
import zio.*
import zio.json.*

/** OAuth 2.0 Client Credentials token request body.
  *
  * Clients POST this to /auth/token to receive a JWT access token.
  * Only grant_type = "client_credentials" is supported.
  */
final case class TokenRequest(
  clientId:     String,
  clientSecret: String,
  grantType:    String = "client_credentials"
)

object TokenRequest:
  given JsonCodec[TokenRequest] = DeriveJsonCodec.gen

object AuthApi:

  /** POST /auth/token
    *
    * Issues a JWT access token given valid client credentials.
    * No Bearer token is required — this is the unauthenticated entry point.
    *
    * Request:  TokenRequest JSON body
    * Response: 200 TokenResponse
    *           400 if grant_type is not "client_credentials"
    *           401 if credentials are invalid
    */
  val tokenEndpoint: PublicEndpoint[TokenRequest, (StatusCode, ErrorResponse), TokenResponse, Any] =
    endpoint
      .post
      .in("auth" / "token")
      .in(jsonBody[TokenRequest].description(
        "Client credentials. grant_type must be 'client_credentials'."
      ))
      .out(jsonBody[TokenResponse].description("JWT access token"))
      .errorOut(statusCode.and(jsonBody[ErrorResponse]))
      .description("Issue a JWT access token via the OAuth 2.0 Client Credentials flow")
      .tag("Authentication")

  // Typed with AppEnv so all server endpoints share the same R for the route list assembly.
  // ZIO's contravariance ensures logic that only uses AuthService still compiles.
  val tokenServerEndpoint: ZServerEndpoint[AppEnv, Any] =
    tokenEndpoint.zServerLogic { req =>
      if req.grantType != "client_credentials" then
        ZIO.fail(StatusCode.BadRequest -> ErrorResponse("grant_type must be 'client_credentials'"))
      else
        AuthService
          .issueToken(req.clientId, req.clientSecret)
          .mapError(authErrorToHttp)
    }

  val endpoints: List[AnyEndpoint]                    = List(tokenEndpoint)
  val serverEndpoints: List[ZServerEndpoint[AppEnv, Any]] = List(tokenServerEndpoint)
