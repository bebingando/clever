package com.clever.photos.api

import com.clever.photos.auth.{AuthError, AuthService}
import com.clever.photos.repository.{ApiClientRepository, PhotoRepository, PhotographerRepository}
import sttp.model.StatusCode
import zio.json.*

/** Full dependency union for every authenticated endpoint.
  *
  * Defining a single `AppEnv` and annotating all [[sttp.tapir.ztapir.ZServerEndpoint]] values
  * with it avoids the need for `asInstanceOf` casts when assembling the route list.
  * ZIO's contravariance in `R` ensures that endpoint logic that uses only a subset
  * of these services still type-checks: `ZIO[AuthService, E, A] <: ZIO[AppEnv, E, A]`.
  */
type AppEnv = PhotoRepository & PhotographerRepository & ApiClientRepository & AuthService

/** Standard error envelope returned for all non-2xx responses. */
final case class ErrorResponse(message: String)

object ErrorResponse:
  given JsonCodec[ErrorResponse] = DeriveJsonCodec.gen

/** Pagination metadata included in list responses. */
final case class PaginationMeta(total: Long, page: Int, perPage: Int)

object PaginationMeta:
  given JsonCodec[PaginationMeta] = DeriveJsonCodec.gen

/** Generic paged list wrapper. */
final case class PagedResponse[A](data: List[A], meta: PaginationMeta)

object PagedResponse:
  given [A: JsonCodec]: JsonCodec[PagedResponse[A]] = DeriveJsonCodec.gen

/** Maps an [[AuthError]] to an HTTP status + error body pair. */
def authErrorToHttp(e: AuthError): (StatusCode, ErrorResponse) = e match
  case AuthError.InvalidCredentials(msg)  => (StatusCode.Unauthorized, ErrorResponse(msg))
  case AuthError.InvalidToken(msg)        => (StatusCode.Unauthorized, ErrorResponse(msg))
  case AuthError.InsufficientScope(scope) => (StatusCode.Forbidden,    ErrorResponse(s"Required scope: $scope"))
  case AuthError.ClientNotFound(id)       => (StatusCode.Unauthorized, ErrorResponse(s"Client not found: $id"))

/** Maps a generic Throwable to HTTP 500. */
def throwableToHttp(e: Throwable): (StatusCode, ErrorResponse) =
  (StatusCode.InternalServerError, ErrorResponse(e.getMessage))
