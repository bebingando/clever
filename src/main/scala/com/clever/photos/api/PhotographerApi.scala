package com.clever.photos.api

import com.clever.photos.auth.AuthService
import com.clever.photos.domain.*
import com.clever.photos.repository.{PhotoRepository, PhotographerRepository}
import sttp.model.StatusCode
import sttp.tapir.AnyEndpoint
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import sttp.tapir.ztapir.*
import zio.*

/** REST endpoints for the /photographers resource, and the nested
  * /photographers/:id/photos convenience route.
  */
object PhotographerApi:

  type Env = PhotographerRepository & PhotoRepository & AuthService

  private def authenticate(token: String): ZIO[AuthService, (StatusCode, ErrorResponse), TokenClaims] =
    AuthService.validateToken(token).mapError(authErrorToHttp)

  private val securedBase =
    endpoint
      .securityIn(auth.bearer[String]().description("JWT access token"))
      .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  // ─── Abstract endpoint definitions ────────────────────────────────────────

  val listPhotographersEndpoint =
    securedBase.get
      .in("photographers")
      .in(query[Option[String]]("name").description("Filter by name (case-insensitive ILIKE)"))
      .in(query[Int]("page").default(1))
      .in(query[Int]("per_page").default(20))
      .out(jsonBody[PagedResponse[Photographer]])
      .description("List all photographers with optional name filter")
      .tag("Photographers")

  val getPhotographerEndpoint =
    securedBase.get
      .in("photographers" / path[Long]("photographer_id"))
      .out(jsonBody[Photographer])
      .description("Get a single photographer by ID")
      .tag("Photographers")

  val getPhotographerPhotosEndpoint =
    securedBase.get
      .in("photographers" / path[Long]("photographer_id") / "photos")
      .in(query[Int]("page").default(1))
      .in(query[Int]("per_page").default(20))
      .out(jsonBody[PagedResponse[Photo]])
      .description(
        "Get all photos by a specific photographer. " +
        "Equivalent to GET /photos?photographer_id=:id but ergonomically nicer."
      )
      .tag("Photographers")

  val createPhotographerEndpoint =
    securedBase.post
      .in("photographers")
      .in(jsonBody[PhotographerCreate])
      .out(statusCode(StatusCode.Created).and(jsonBody[Photographer]))
      .description("Create a new photographer record")
      .tag("Photographers")

  val replacePhotographerEndpoint =
    securedBase.put
      .in("photographers" / path[Long]("photographer_id"))
      .in(jsonBody[PhotographerReplace])
      .out(jsonBody[Photographer])
      .description("Replace a photographer record entirely (PUT semantics)")
      .tag("Photographers")

  val patchPhotographerEndpoint =
    securedBase.patch
      .in("photographers" / path[Long]("photographer_id"))
      .in(jsonBody[PhotographerPatch])
      .out(jsonBody[Photographer])
      .description("Partially update a photographer (PATCH semantics)")
      .tag("Photographers")

  /** DELETE /photographers/:id
    *
    * Restricted to the `admin` scope because of the ON DELETE RESTRICT
    * foreign key constraint: deleting a photographer with photos will fail
    * at the DB level. Callers must first delete or reassign all photos.
    * Returns 409 Conflict with a clear message if photos exist.
    */
  val deletePhotographerEndpoint =
    securedBase.delete
      .in("photographers" / path[Long]("photographer_id"))
      .out(statusCode(StatusCode.NoContent))
      .description(
        "Delete a photographer. Requires the 'admin' scope. " +
        "Returns 409 Conflict if the photographer still has photos — " +
        "delete or reassign photos first."
      )
      .tag("Photographers")

  // ─── Server endpoints ──────────────────────────────────────────────────────

  val listPhotographersServer: ZServerEndpoint[Env, Any] =
    listPhotographersEndpoint.zServerSecurityLogic(authenticate).serverLogic { claims => input =>
      val (name, page, perPage) = input
      for
        _ <- ZIO.serviceWithZIO[AuthService](_.requireScope(claims, Scopes.PhotosRead))
                 .mapError(authErrorToHttp)
        q = PhotographerQuery(name = name, page = page.max(1), perPage = perPage.min(100).max(1))
        (rows, total) <- ZIO.serviceWithZIO[PhotographerRepository](_.findAll(q))
                             .mapError(throwableToHttp)
      yield PagedResponse(rows, PaginationMeta(total, q.page, q.perPage))
    }

  val getPhotographerServer: ZServerEndpoint[Env, Any] =
    getPhotographerEndpoint.zServerSecurityLogic(authenticate).serverLogic { claims => pgId =>
      for
        _ <- ZIO.serviceWithZIO[AuthService](_.requireScope(claims, Scopes.PhotosRead))
                 .mapError(authErrorToHttp)
        r <- ZIO.serviceWithZIO[PhotographerRepository](_.findById(pgId))
                 .mapError(throwableToHttp)
        p <- ZIO.fromOption(r)
                 .mapError(_ => StatusCode.NotFound -> ErrorResponse(s"Photographer not found: $pgId"))
      yield p
    }

  val getPhotographerPhotosServer: ZServerEndpoint[Env, Any] =
    getPhotographerPhotosEndpoint.zServerSecurityLogic(authenticate).serverLogic { claims => input =>
      val (pgId, page, perPage) = input
      for
        _ <- ZIO.serviceWithZIO[AuthService](_.requireScope(claims, Scopes.PhotosRead))
                 .mapError(authErrorToHttp)
        exists <- ZIO.serviceWithZIO[PhotographerRepository](_.existsById(pgId))
                      .mapError(throwableToHttp)
        _ <- ZIO.unless(exists)(
                 ZIO.fail(StatusCode.NotFound -> ErrorResponse(s"Photographer not found: $pgId"))
               )
        (rows, total) <- ZIO.serviceWithZIO[PhotoRepository](
                             _.findByPhotographerId(pgId, page.max(1), perPage.min(100).max(1))
                           ).mapError(throwableToHttp)
      yield PagedResponse(rows, PaginationMeta(total, page, perPage))
    }

  val createPhotographerServer: ZServerEndpoint[Env, Any] =
    createPhotographerEndpoint.zServerSecurityLogic(authenticate).serverLogic { claims => body =>
      for
        _ <- ZIO.serviceWithZIO[AuthService](_.requireScope(claims, Scopes.PhotographersWrite))
                 .mapError(authErrorToHttp)
        pg = Photographer(body.photographerId, body.name, body.profileUrl)
        r <- ZIO.serviceWithZIO[PhotographerRepository](_.create(pg))
                 .mapError(throwableToHttp)
      yield r
    }

  val replacePhotographerServer: ZServerEndpoint[Env, Any] =
    replacePhotographerEndpoint.zServerSecurityLogic(authenticate).serverLogic { claims => input =>
      val (pgId, body) = input
      for
        _ <- ZIO.serviceWithZIO[AuthService](_.requireScope(claims, Scopes.PhotographersWrite))
                   .mapError(authErrorToHttp)
        r <- ZIO.serviceWithZIO[PhotographerRepository](_.replace(pgId, body))
                 .mapError(throwableToHttp)
        p <- ZIO.fromOption(r)
                 .mapError(_ => StatusCode.NotFound -> ErrorResponse(s"Photographer not found: $pgId"))
      yield p
    }

  val patchPhotographerServer: ZServerEndpoint[Env, Any] =
    patchPhotographerEndpoint.zServerSecurityLogic(authenticate).serverLogic { claims => input =>
      val (pgId, body) = input
      for
        _ <- ZIO.serviceWithZIO[AuthService](_.requireScope(claims, Scopes.PhotographersWrite))
                   .mapError(authErrorToHttp)
        r <- ZIO.serviceWithZIO[PhotographerRepository](_.patch(pgId, body))
                 .mapError(throwableToHttp)
        p <- ZIO.fromOption(r)
                 .mapError(_ => StatusCode.NotFound -> ErrorResponse(s"Photographer not found: $pgId"))
      yield p
    }

  val deletePhotographerServer: ZServerEndpoint[Env, Any] =
    deletePhotographerEndpoint.zServerSecurityLogic(authenticate).serverLogic { claims => pgId =>
      for
        _ <- ZIO.serviceWithZIO[AuthService](_.requireScope(claims, Scopes.Admin))
                 .mapError(authErrorToHttp)
        photoCount <- ZIO.serviceWithZIO[PhotoRepository](_.countByPhotographerId(pgId))
                          .mapError(throwableToHttp)
        _ <- ZIO.when(photoCount > 0)(
                 ZIO.fail(
                   StatusCode.Conflict -> ErrorResponse(
                     s"Cannot delete photographer $pgId: $photoCount photo(s) still exist. " +
                     "Delete or reassign photos first."
                   )
                 )
               )
        deleted <- ZIO.serviceWithZIO[PhotographerRepository](_.delete(pgId))
                       .mapError(throwableToHttp)
        _ <- ZIO.unless(deleted)(
                 ZIO.fail(StatusCode.NotFound -> ErrorResponse(s"Photographer not found: $pgId"))
               )
      yield ()
    }

  val endpoints: List[AnyEndpoint] = List(
    listPhotographersEndpoint, getPhotographerEndpoint, getPhotographerPhotosEndpoint,
    createPhotographerEndpoint, replacePhotographerEndpoint,
    patchPhotographerEndpoint, deletePhotographerEndpoint
  )

  val serverEndpoints: List[ZServerEndpoint[Env, Any]] = List(
    listPhotographersServer, getPhotographerServer, getPhotographerPhotosServer,
    createPhotographerServer, replacePhotographerServer,
    patchPhotographerServer, deletePhotographerServer
  )
