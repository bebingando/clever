package com.clever.photos.api

import com.clever.photos.auth.{AuthError, AuthService}
import com.clever.photos.repository.{ApiClientRepository, PhotoRepository, PhotographerRepository}
import org.postgresql.util.PSQLException
import sttp.model.StatusCode
import zio.*
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
  case AuthError.ServiceUnavailable(_)    => (StatusCode.ServiceUnavailable, ErrorResponse("Service temporarily unavailable"))
  case AuthError.RateLimitExceeded        => (StatusCode.TooManyRequests, ErrorResponse("Too many authentication attempts. Try again later."))

/** Maps a Throwable to an HTTP error, inspecting PSQLException SQL states
  * for constraint violations and returning a generic message for all other errors
  * (never exposes internal exception details to callers).
  */
def throwableToHttp(e: Throwable): (StatusCode, ErrorResponse) = e match
  case p: PSQLException => p.getSQLState match
    case "23503" => (StatusCode.Conflict, ErrorResponse("Referenced resource does not exist"))
    case "23505" => (StatusCode.Conflict, ErrorResponse("A record with this ID already exists"))
    case _       => (StatusCode.InternalServerError, ErrorResponse("An internal server error occurred"))
  case _ => (StatusCode.InternalServerError, ErrorResponse("An internal server error occurred"))

/** Extension method that maps `Throwable` errors to HTTP error pairs,
  * logging unexpected errors before mapping (constraint violations are expected
  * and not logged).
  */
extension [R, A](zio: ZIO[R, Throwable, A])
  def mapErrorHttp: ZIO[R, (StatusCode, ErrorResponse), A] =
    zio
      .tapError {
        case _: PSQLException => ZIO.unit  // constraint violations are expected
        case e => ZIO.logError(s"Unexpected error: ${e.getMessage}")
      }
      .mapError(throwableToHttp)
