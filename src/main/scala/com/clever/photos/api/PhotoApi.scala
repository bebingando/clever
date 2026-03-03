package com.clever.photos.api

import com.clever.photos.auth.AuthService
import com.clever.photos.domain.*
import com.clever.photos.repository.PhotoRepository
import sttp.model.StatusCode
import sttp.tapir.AnyEndpoint
import sttp.tapir.json.zio.*
import sttp.tapir.generic.auto.*
import sttp.tapir.ztapir.*
import zio.*

import ApiSecurity.{securedBase, authenticate}

/** All REST endpoints for the /photos resource.
  *
  * Each endpoint is defined in two parts:
  *  1. Abstract Tapir definition — no ZIO, just types. Used to generate OpenAPI docs.
  *  2. ZServerEndpoint — abstract definition + server logic (ZIO effects).
  *
  * Auth:  every endpoint requires a Bearer JWT in the Authorization header.
  *        The `securedBase` helper validates the token first; the server logic
  *        then checks the required scope.
  */
object PhotoApi:

  // ─── Endpoint definitions (abstract — no server logic) ────────────────────

  val listPhotosEndpoint =
    securedBase.get
      .in("photos")
      .in(query[Option[Long]]("photographer_id").description("Filter by photographer"))
      .in(query[Option[String]]("alt").description("Full-text search on alt text (stemmed, AND-joined)"))
      .in(query[Option[Int]]("width").description("Exact width match"))
      .in(query[Option[Int]]("height").description("Exact height match"))
      .in(query[Option[Int]]("min_width").description("Minimum width"))
      .in(query[Option[Int]]("min_height").description("Minimum height"))
      .in(query[Option[String]]("avg_color").description("Exact hex colour match, e.g. %23333831"))
      .in(query[Int]("page").default(1).description("Page number (1-based)"))
      .in(query[Int]("per_page").default(20).description("Items per page (max 100)"))
      .out(jsonBody[PagedResponse[Photo]].description("Paged list of photos"))
      .description("List or search photos with optional filters")
      .tag("Photos")

  val getPhotoEndpoint =
    securedBase.get
      .in("photos" / path[Long]("id").description("Photo ID"))
      .out(jsonBody[Photo].description("Photo record"))
      .description("Get a single photo by ID")
      .tag("Photos")

  val createPhotoEndpoint =
    securedBase.post
      .in("photos")
      .in(jsonBody[PhotoCreate].description("New photo data; id must be provided"))
      .out(statusCode(StatusCode.Created).and(jsonBody[Photo].description("Created photo")))
      .description("Create a new photo record")
      .tag("Photos")

  val replacePhotoEndpoint =
    securedBase.put
      .in("photos" / path[Long]("id"))
      .in(jsonBody[PhotoReplace].description("Full replacement — all fields required"))
      .out(jsonBody[Photo].description("Updated photo"))
      .description("Replace a photo record entirely (PUT semantics)")
      .tag("Photos")

  val patchPhotoEndpoint =
    securedBase.patch
      .in("photos" / path[Long]("id"))
      .in(jsonBody[PhotoPatch].description("Partial update — only provided fields are changed"))
      .out(jsonBody[Photo].description("Updated photo"))
      .description("Partially update a photo record (PATCH semantics)")
      .tag("Photos")

  val deletePhotoEndpoint =
    securedBase.delete
      .in("photos" / path[Long]("id"))
      .out(statusCode(StatusCode.NoContent).description("Photo deleted"))
      .description("Delete a photo by ID")
      .tag("Photos")

  // ─── Server endpoints (with ZIO logic) ────────────────────────────────────

  // Use the shared AppEnv so all server endpoints share the same R.
  type Env = AppEnv

  val listPhotosServer: ZServerEndpoint[Env, Any] =
    listPhotosEndpoint.zServerSecurityLogic(authenticate).serverLogic { claims => input =>
      val (photographerId, alt, width, height, minWidth, minHeight, avgColor, page, perPage) = input
      for
        _ <- ZIO.serviceWithZIO[AuthService](_.requireScope(claims, Scopes.PhotosRead))
                 .mapError(authErrorToHttp)
        perPageClamped = perPage.min(100).max(1)
        q = PhotoQuery(
              photographerId = photographerId,
              alt            = alt,
              width          = width,
              height         = height,
              minWidth       = minWidth,
              minHeight      = minHeight,
              avgColor       = avgColor,
              page           = page.max(1),
              perPage        = perPageClamped
            )
        (rows, total) <- ZIO.serviceWithZIO[PhotoRepository](_.findAll(q)).mapErrorHttp
      yield PagedResponse(rows, PaginationMeta(total, q.page, q.perPage))
    }

  val getPhotoServer: ZServerEndpoint[Env, Any] =
    getPhotoEndpoint.zServerSecurityLogic(authenticate).serverLogic { claims => photoId =>
      for
        _ <- ZIO.serviceWithZIO[AuthService](_.requireScope(claims, Scopes.PhotosRead))
                 .mapError(authErrorToHttp)
        r <- ZIO.serviceWithZIO[PhotoRepository](_.findById(photoId)).mapErrorHttp
        p <- ZIO.fromOption(r)
                 .mapError(_ => StatusCode.NotFound -> ErrorResponse(s"Photo not found: $photoId"))
      yield p
    }

  val createPhotoServer: ZServerEndpoint[Env, Any] =
    createPhotoEndpoint.zServerSecurityLogic(authenticate).serverLogic { claims => body =>
      for
        _ <- ZIO.serviceWithZIO[AuthService](_.requireScope(claims, Scopes.PhotosWrite))
                 .mapError(authErrorToHttp)
        // Validate avg_color format
        _ <- ZIO.unless(body.avgColor.matches("^#[0-9A-Fa-f]{6}$"))(
                 ZIO.fail(StatusCode.UnprocessableEntity -> ErrorResponse(
                   s"avgColor must match #RRGGBB format, got '${body.avgColor}'"
                 ))
               )
        // Validate dimensions
        _ <- ZIO.when(body.width <= 0 || body.height <= 0)(
                 ZIO.fail(StatusCode.UnprocessableEntity -> ErrorResponse(
                   "width and height must be positive integers"
                 ))
               )
        // FK constraint on photographer_id is enforced by the DB (PSQLException 23503 → 409).
        photo = Photo(
                  id             = body.id,
                  photographerId = body.photographerId,
                  width          = body.width,
                  height         = body.height,
                  pexelsUrl      = body.pexelsUrl,
                  baseImageUrl   = body.baseImageUrl,
                  avgColor       = body.avgColor,
                  alt            = body.alt
                )
        created <- ZIO.serviceWithZIO[PhotoRepository](_.create(photo)).mapErrorHttp
      yield created
    }

  val replacePhotoServer: ZServerEndpoint[Env, Any] =
    replacePhotoEndpoint.zServerSecurityLogic(authenticate).serverLogic { claims => input =>
      val (photoId, body) = input
      for
        _ <- ZIO.serviceWithZIO[AuthService](_.requireScope(claims, Scopes.PhotosWrite))
                 .mapError(authErrorToHttp)
        _ <- ZIO.unless(body.avgColor.matches("^#[0-9A-Fa-f]{6}$"))(
                   ZIO.fail(StatusCode.UnprocessableEntity -> ErrorResponse(
                     s"avgColor must match #RRGGBB format"
                   ))
                 )
        r <- ZIO.serviceWithZIO[PhotoRepository](_.replace(photoId, body)).mapErrorHttp
        p <- ZIO.fromOption(r)
                 .mapError(_ => StatusCode.NotFound -> ErrorResponse(s"Photo not found: $photoId"))
      yield p
    }

  val patchPhotoServer: ZServerEndpoint[Env, Any] =
    patchPhotoEndpoint.zServerSecurityLogic(authenticate).serverLogic { claims => input =>
      val (photoId, body) = input
      for
        _ <- ZIO.serviceWithZIO[AuthService](_.requireScope(claims, Scopes.PhotosWrite))
                 .mapError(authErrorToHttp)
        _ <- body.avgColor.fold(ZIO.unit)(c =>
                 ZIO.unless(c.matches("^#[0-9A-Fa-f]{6}$"))(
                   ZIO.fail(StatusCode.UnprocessableEntity -> ErrorResponse(
                     s"avgColor must match #RRGGBB format"
                   ))
                 )
               )
        r <- ZIO.serviceWithZIO[PhotoRepository](_.patch(photoId, body)).mapErrorHttp
        p <- ZIO.fromOption(r)
                 .mapError(_ => StatusCode.NotFound -> ErrorResponse(s"Photo not found: $photoId"))
      yield p
    }

  val deletePhotoServer: ZServerEndpoint[Env, Any] =
    deletePhotoEndpoint.zServerSecurityLogic(authenticate).serverLogic { claims => photoId =>
      for
        _ <- ZIO.serviceWithZIO[AuthService](_.requireScope(claims, Scopes.PhotosDelete))
                 .mapError(authErrorToHttp)
        deleted <- ZIO.serviceWithZIO[PhotoRepository](_.delete(photoId)).mapErrorHttp
        _ <- ZIO.unless(deleted)(
                 ZIO.fail(StatusCode.NotFound -> ErrorResponse(s"Photo not found: $photoId"))
               )
      yield ()
    }

  val endpoints: List[AnyEndpoint] = List(
    listPhotosEndpoint, getPhotoEndpoint, createPhotoEndpoint,
    replacePhotoEndpoint, patchPhotoEndpoint, deletePhotoEndpoint
  )

  val serverEndpoints: List[ZServerEndpoint[Env, Any]] = List(
    listPhotosServer, getPhotoServer, createPhotoServer,
    replacePhotoServer, patchPhotoServer, deletePhotoServer
  )
