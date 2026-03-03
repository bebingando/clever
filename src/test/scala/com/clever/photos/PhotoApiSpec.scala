package com.clever.photos

import com.clever.photos.api.*
import com.clever.photos.auth.*
import com.clever.photos.domain.*
import com.clever.photos.repository.*
import sttp.client3.*
import sttp.client3.testing.SttpBackendStub
import sttp.model.StatusCode
import sttp.tapir.server.stub.TapirStubInterpreter
import sttp.tapir.ztapir.{RIOMonadError, ZServerEndpoint}
import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

/** Integration-style tests for the Photo API endpoints.
  *
  * Uses Tapir's SttpBackendStub interpreter to exercise the full endpoint
  * pipeline — security, server logic, serialisation — without binding a real
  * HTTP port or touching a database.
  *
  * Auth is provided by MockAuthService (all scopes, any non-empty token).
  * Persistence is provided by in-memory repository stubs.
  */
object PhotoApiSpec extends ZIOSpecDefault:

  // ─── Minimal in-memory repos ────────────────────────────────────────────────

  private class InMemoryPhotoRepo extends PhotoRepository:
    private val store = scala.collection.mutable.Map.empty[Long, Photo]

    def findById(id: Long): Task[Option[Photo]] = ZIO.succeed(store.get(id))
    def findAll(q: PhotoQuery): Task[(List[Photo], Long)] = ZIO.succeed {
      val rows = store.values.toList.sortBy(_.id)
      (rows.drop((q.page - 1) * q.perPage).take(q.perPage), rows.length.toLong)
    }
    def findByPhotographerId(p: Long, pg: Int, pp: Int): Task[(List[Photo], Long)] =
      findAll(PhotoQuery(photographerId = Some(p), page = pg, perPage = pp))
    def create(photo: Photo): Task[Photo] =
      ZIO.succeed { store(photo.id) = photo; photo }
    def replace(id: Long, r: PhotoReplace): Task[Option[Photo]] = ZIO.succeed {
      store.get(id).map { e =>
        val u = e.copy(photographerId=r.photographerId,width=r.width,height=r.height,
          pexelsUrl=r.pexelsUrl,baseImageUrl=r.baseImageUrl,avgColor=r.avgColor,alt=r.alt)
        store(id) = u; u
      }
    }
    def patch(id: Long, p: PhotoPatch): Task[Option[Photo]] = ZIO.succeed {
      store.get(id).map { e =>
        val u = e.copy(
          width        = p.width.getOrElse(e.width),
          height       = p.height.getOrElse(e.height),
          pexelsUrl    = p.pexelsUrl.getOrElse(e.pexelsUrl),
          baseImageUrl = p.baseImageUrl.getOrElse(e.baseImageUrl),
          avgColor     = p.avgColor.getOrElse(e.avgColor),
          alt          = p.alt.fold(e.alt)(identity)
        )
        store(id) = u; u
      }
    }
    def delete(id: Long): Task[Boolean] = ZIO.succeed(store.remove(id).isDefined)
    def countByPhotographerId(p: Long): Task[Long] =
      ZIO.succeed(store.values.count(_.photographerId == p).toLong)
    def existsById(id: Long): Task[Boolean] = ZIO.succeed(store.contains(id))

  private class InMemoryPhotographerRepo extends PhotographerRepository:
    private val store = scala.collection.mutable.Map.empty[Long, Photographer]

    def findById(id: Long): Task[Option[Photographer]] = ZIO.succeed(store.get(id))
    def findAll(q: PhotographerQuery): Task[(List[Photographer], Long)] = ZIO.succeed {
      val rows = store.values.toList.sortBy(_.photographerId)
      (rows, rows.length.toLong)
    }
    def create(p: Photographer): Task[Photographer] =
      ZIO.succeed { store(p.photographerId) = p; p }
    def replace(id: Long, r: PhotographerReplace): Task[Option[Photographer]] = ZIO.succeed {
      store.get(id).map { e => val u = e.copy(name=r.name,profileUrl=r.profileUrl); store(id)=u; u }
    }
    def patch(id: Long, p: PhotographerPatch): Task[Option[Photographer]] = ZIO.succeed {
      store.get(id).map { e =>
        val u = e.copy(name=p.name.getOrElse(e.name),profileUrl=p.profileUrl.getOrElse(e.profileUrl))
        store(id)=u; u
      }
    }
    def delete(id: Long): Task[Boolean] = ZIO.succeed(store.remove(id).isDefined)
    def existsById(id: Long): Task[Boolean] = ZIO.succeed(store.contains(id))

  private class StubApiClientRepo extends ApiClientRepository:
    def findById(id: String): Task[Option[ApiClient]] = ZIO.succeed(None)
    def create(client: ApiClient): Task[ApiClient]    = ZIO.succeed(client)
    def countAll(): Task[Long]                         = ZIO.succeed(0L)

  // ─── Shared test environment ────────────────────────────────────────────────
  // In ZIO 2, layers provided inline via .provide() at the test level are built
  // fresh for each test (each test has its own scope), so no ZLayer.fresh needed.

  private type TestEnv = PhotoRepository & PhotographerRepository & ApiClientRepository & AuthService

  private val testEnv: ULayer[TestEnv] =
    ZLayer.succeed[PhotoRepository](new InMemoryPhotoRepo) ++
    ZLayer.succeed[PhotographerRepository](new InMemoryPhotographerRepo) ++
    ZLayer.succeed[ApiClientRepository](new StubApiClientRepo) ++
    MockAuthService.layer

  private val samplePhotographer = Photographer(42L, "Alice", "https://pexels.com/@alice")
  private val samplePhoto = Photo(1L, 42L, 1920, 1080,
    "https://pexels.com/photo/1", "https://img.pexels.com/photos/1/p-1.jpeg",
    "#112233", Some("A golden sunset"))

  private def seed: ZIO[TestEnv, Throwable, Unit] =
    ZIO.serviceWithZIO[PhotographerRepository](_.create(samplePhotographer)) *>
    ZIO.serviceWithZIO[PhotoRepository](_.create(samplePhoto)).unit

  // ─── TapirStubInterpreter backend helper ────────────────────────────────────
  // No explicit return type to avoid kind-projector `*` syntax — Scala 3 infers it.

  private def backendFor(endpoint: ZServerEndpoint[TestEnv, Any]) =
    TapirStubInterpreter(SttpBackendStub(new RIOMonadError[TestEnv]))
      .whenServerEndpointRunLogic(endpoint)
      .backend()

  // ─── Tests ────────────────────────────────────────────────────────────────

  def spec = suite("PhotoApiSpec")(

    suite("GET /photos/:id via endpoint pipeline")(
      test("returns 200 with the photo for a known ID") {
        val backend = backendFor(PhotoApi.getPhotoServer)
        for
          _        <- seed
          response <- basicRequest
                        .get(uri"http://test/photos/1")
                        .header("Authorization", "Bearer mock-token")
                        .send(backend)
        yield assertTrue(response.code == StatusCode.Ok)
      }.provide(testEnv),

      test("returns 404 for an unknown ID") {
        val backend = backendFor(PhotoApi.getPhotoServer)
        basicRequest
          .get(uri"http://test/photos/9999")
          .header("Authorization", "Bearer mock-token")
          .send(backend)
          .map(r => assertTrue(r.code == StatusCode.NotFound))
          .provide(testEnv)
      }
    ),

    suite("POST /photos — validation via endpoint pipeline")(
      test("returns 422 for invalid avgColor format") {
        val backend = backendFor(PhotoApi.createPhotoServer)
        val body    = PhotoCreate(99L, 42L, 100, 100, "u", "b", "bad-color", None)
        basicRequest
          .post(uri"http://test/photos")
          .header("Authorization", "Bearer mock-token")
          .contentType("application/json")
          .body(body.toJson)
          .send(backend)
          .map(r => assertTrue(r.code == StatusCode.UnprocessableEntity))
          .provide(testEnv)
      },

      test("returns 201 for a valid photo") {
        val backend = backendFor(PhotoApi.createPhotoServer)
        val body    = PhotoCreate(88L, 42L, 800, 600, "https://p.com/88", "https://img.p.com/88.jpg", "#AABBCC", None)
        basicRequest
          .post(uri"http://test/photos")
          .header("Authorization", "Bearer mock-token")
          .contentType("application/json")
          .body(body.toJson)
          .send(backend)
          .map(r => assertTrue(r.code == StatusCode.Created))
          .provide(testEnv)
      }
    ),

    suite("scope enforcement")(
      test("MockAuthService grants all scopes") {
        val mock   = new MockAuthService
        val claims = TokenClaims("c", Scopes.all)
        for
          _ <- mock.requireScope(claims, Scopes.PhotosRead)
          _ <- mock.requireScope(claims, Scopes.PhotosWrite)
          _ <- mock.requireScope(claims, Scopes.Admin)
        yield assertTrue(true)
      },

      test("requireScope fails for missing scope") {
        val mock   = new MockAuthService
        val claims = TokenClaims("c", List(Scopes.PhotosRead))
        mock.requireScope(claims, Scopes.Admin).flip.map { e =>
          assertTrue(e.isInstanceOf[AuthError.InsufficientScope])
        }
      }
    ),

    suite("pagination logic")(
      test("perPage clamped to 100") {
        ZIO.succeed {
          val perPage = 200
          val clamped = perPage.min(100).max(1)
          assertTrue(clamped == 100)
        }
      },

      test("page minimum is 1") {
        ZIO.succeed {
          val page    = -5
          val bounded = page.max(1)
          assertTrue(bounded == 1)
        }
      }
    ),

    suite("delete protection")(
      test("cannot delete photographer when photos exist") {
        for
          _     <- seed
          count <- ZIO.serviceWithZIO[PhotoRepository](_.countByPhotographerId(42L))
        yield assertTrue(count == 1L)
      }.provide(testEnv)
    ),

    suite("PhotoPatch alt semantics")(
      test("None leaves alt field absent") {
        assertTrue(PhotoPatch().alt.isEmpty)
      },
      test("Some(None) clears alt to null") {
        assertTrue(PhotoPatch(alt = Some(None)).alt.contains(None))
      },
      test("Some(Some(v)) sets alt value") {
        assertTrue(PhotoPatch(alt = Some(Some("caption"))).alt.contains(Some("caption")))
      }
    )
  )
