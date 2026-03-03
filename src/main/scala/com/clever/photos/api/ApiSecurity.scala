package com.clever.photos.api

import com.clever.photos.auth.AuthService
import com.clever.photos.domain.TokenClaims
import sttp.model.StatusCode
import sttp.tapir.generic.auto.*
import sttp.tapir.json.zio.*
import sttp.tapir.ztapir.*
import zio.*

/** Shared security primitives reused by every authenticated API object.
  *
  * Centralising `securedBase` and `authenticate` here eliminates duplication
  * across [[PhotoApi]] and [[PhotographerApi]] and keeps the JWT validation
  * logic in a single place.
  */
object ApiSecurity:

  /** Base endpoint with a Bearer JWT security input and a typed error output. */
  val securedBase =
    endpoint
      .securityIn(auth.bearer[String]().description("JWT access token from POST /auth/token"))
      .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  /** Validate a Bearer JWT token, mapping [[AuthError]] to an HTTP error pair. */
  def authenticate(token: String): ZIO[AuthService, (StatusCode, ErrorResponse), TokenClaims] =
    AuthService.validateToken(token).mapError(authErrorToHttp)
