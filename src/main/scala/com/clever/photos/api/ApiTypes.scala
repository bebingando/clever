package com.clever.photos.api

import com.clever.photos.auth.AuthError
import sttp.model.StatusCode
import zio.json.*

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
