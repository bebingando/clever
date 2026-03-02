package com.clever.photos

import com.clever.photos.domain.*
import com.clever.photos.repository.{PhotoRepository, PhotographerRepository, LivePhotoRepository, LivePhotographerRepository}
import zio.*
import zio.test.*
import zio.test.Assertion.*

/** Unit tests for the PhotoRepository logic using an in-memory stub.
  *
  * These tests exercise the pagination, filtering, and CRUD logic without
  * requiring a real database connection.  A separate integration-test suite
  * (not included here to respect scope) would use Testcontainers to spin up
  * a real PostgreSQL instance.
  */
object PhotoRepositorySpec extends ZIOSpecDefault:

  // ─── In-memory PhotoRepository stub ────────────────────────────────────────

  private class InMemoryPhotoRepository extends PhotoRepository:
    private val store = scala.collection.mutable.Map.empty[Long, Photo]

    def findById(id: Long): Task[Option[Photo]] =
      ZIO.succeed(store.get(id))

    def findAll(q: PhotoQuery): Task[(List[Photo], Long)] = ZIO.succeed {
      var rows = store.values.toList
      rows = q.photographerId.fold(rows)(id => rows.filter(_.photographerId == id))
      rows = q.width.fold(rows)(w   => rows.filter(_.width == w))
      rows = q.height.fold(rows)(h  => rows.filter(_.height == h))
      rows = q.minWidth.fold(rows)(w  => rows.filter(_.width >= w))
      rows = q.minHeight.fold(rows)(h => rows.filter(_.height >= h))
      rows = q.avgColor.fold(rows)(c  => rows.filter(_.avgColor == c))
      rows = q.alt.fold(rows)(kw => {
        val lower = kw.toLowerCase
        rows.filter(_.alt.exists(_.toLowerCase.contains(lower)))
      })
      val total  = rows.length.toLong
      val page   = q.page.max(1)
      val paged  = rows.sortBy(_.id).drop((page - 1) * q.perPage).take(q.perPage)
      (paged, total)
    }

    def findByPhotographerId(pgId: Long, page: Int, perPage: Int): Task[(List[Photo], Long)] =
      findAll(PhotoQuery(photographerId = Some(pgId), page = page, perPage = perPage))

    def create(photo: Photo): Task[Photo] = ZIO.attempt {
      if store.contains(photo.id) then
        throw new RuntimeException(s"Duplicate key: ${photo.id}")
      store(photo.id) = photo
      photo
    }

    def replace(id: Long, r: PhotoReplace): Task[Option[Photo]] = ZIO.succeed {
      store.get(id).map { existing =>
        val updated = existing.copy(
          photographerId = r.photographerId, width = r.width, height = r.height,
          pexelsUrl = r.pexelsUrl, baseImageUrl = r.baseImageUrl,
          avgColor = r.avgColor, alt = r.alt
        )
        store(id) = updated
        updated
      }
    }

    def patch(id: Long, p: PhotoPatch): Task[Option[Photo]] = ZIO.succeed {
      store.get(id).map { existing =>
        val updated = existing.copy(
          width        = p.width.getOrElse(existing.width),
          height       = p.height.getOrElse(existing.height),
          pexelsUrl    = p.pexelsUrl.getOrElse(existing.pexelsUrl),
          baseImageUrl = p.baseImageUrl.getOrElse(existing.baseImageUrl),
          avgColor     = p.avgColor.getOrElse(existing.avgColor),
          alt          = p.alt.orElse(existing.alt)
        )
        store(id) = updated
        updated
      }
    }

    def delete(id: Long): Task[Boolean] = ZIO.succeed(store.remove(id).isDefined)

    def countByPhotographerId(pgId: Long): Task[Long] =
      ZIO.succeed(store.values.count(_.photographerId == pgId).toLong)

    def existsById(id: Long): Task[Boolean] =
      ZIO.succeed(store.contains(id))

  private val repoLayer: ULayer[PhotoRepository] =
    ZLayer.succeed(new InMemoryPhotoRepository)

  // ─── Test fixtures ─────────────────────────────────────────────────────────

  private val photo1 = Photo(1L, 100L, 1920, 1080, "https://pexels.com/1", "https://img.pexels.com/1.jpeg", "#AABBCC", Some("A sunny beach"))
  private val photo2 = Photo(2L, 100L, 1280,  720, "https://pexels.com/2", "https://img.pexels.com/2.jpeg", "#112233", Some("Mountain sunset"))
  private val photo3 = Photo(3L, 200L, 3840, 2160, "https://pexels.com/3", "https://img.pexels.com/3.jpeg", "#AABBCC", None)

  private val seededLayer: ULayer[PhotoRepository] =
    ZLayer.fromZIO {
      val repo = new InMemoryPhotoRepository
      (repo.create(photo1) *>
       repo.create(photo2) *>
       repo.create(photo3)).orDie *>
      ZIO.succeed[PhotoRepository](repo)
    }

  // ─── Tests ────────────────────────────────────────────────────────────────

  def spec = suite("PhotoRepositorySpec")(

    suite("findById")(
      test("returns Some for an existing photo") {
        ZIO.serviceWithZIO[PhotoRepository](_.findById(1L)).map { r =>
          assertTrue(r.contains(photo1))
        }.provide(seededLayer)
      },

      test("returns None for a non-existent ID") {
        ZIO.serviceWithZIO[PhotoRepository](_.findById(9999L)).map { r =>
          assertTrue(r.isEmpty)
        }.provide(seededLayer)
      }
    ),

    suite("findAll")(
      test("returns all photos with default query") {
        ZIO.serviceWithZIO[PhotoRepository](_.findAll(PhotoQuery())).map { case (rows, total) =>
          assertTrue(rows.length == 3) && assertTrue(total == 3L)
        }.provide(seededLayer)
      },

      test("filters by photographer_id") {
        ZIO.serviceWithZIO[PhotoRepository](
          _.findAll(PhotoQuery(photographerId = Some(100L)))
        ).map { case (rows, total) =>
          assertTrue(total == 2L) && assertTrue(rows.forall(_.photographerId == 100L))
        }.provide(seededLayer)
      },

      test("filters by exact dimensions") {
        ZIO.serviceWithZIO[PhotoRepository](
          _.findAll(PhotoQuery(width = Some(1920), height = Some(1080)))
        ).map { case (rows, _) =>
          assertTrue(rows == List(photo1))
        }.provide(seededLayer)
      },

      test("filters by avg_color") {
        ZIO.serviceWithZIO[PhotoRepository](
          _.findAll(PhotoQuery(avgColor = Some("#AABBCC")))
        ).map { case (rows, total) =>
          assertTrue(total == 2L)
        }.provide(seededLayer)
      },

      test("full-text search on alt (case-insensitive substring for stub)") {
        ZIO.serviceWithZIO[PhotoRepository](
          _.findAll(PhotoQuery(alt = Some("beach")))
        ).map { case (rows, _) =>
          assertTrue(rows.map(_.id) == List(1L))
        }.provide(seededLayer)
      },

      test("paginates correctly") {
        ZIO.serviceWithZIO[PhotoRepository](
          _.findAll(PhotoQuery(page = 1, perPage = 2))
        ).map { case (rows, total) =>
          assertTrue(total == 3L) && assertTrue(rows.length == 2)
        }.provide(seededLayer)
      }
    ),

    suite("create")(
      test("inserts a new photo and findById returns it") {
        val repo  = new InMemoryPhotoRepository
        val newPh = Photo(99L, 300L, 100, 100, "url", "base", "#000000", None)
        for
          created <- repo.create(newPh)
          found   <- repo.findById(99L)
        yield assertTrue(found.contains(newPh))
      }
    ),

    suite("replace")(
      test("updates all fields and returns the updated photo") {
        ZIO.serviceWithZIO[PhotoRepository] { repo =>
          val r = PhotoReplace(100L, 800, 600, "new-url", "new-base", "#FFFFFF", Some("new alt"))
          for
            result <- repo.replace(1L, r)
          yield
            assertTrue(result.isDefined) &&
            assertTrue(result.get.width == 800) &&
            assertTrue(result.get.avgColor == "#FFFFFF")
        }.provide(seededLayer)
      },

      test("returns None for non-existent ID") {
        ZIO.serviceWithZIO[PhotoRepository](
          _.replace(9999L, PhotoReplace(100L, 1, 1, "u", "b", "#000000", None))
        ).map(r => assertTrue(r.isEmpty)).provide(seededLayer)
      }
    ),

    suite("patch")(
      test("updates only the provided fields") {
        ZIO.serviceWithZIO[PhotoRepository] { repo =>
          for
            result <- repo.patch(1L, PhotoPatch(width = Some(999)))
          yield
            assertTrue(result.isDefined) &&
            assertTrue(result.get.width == 999) &&
            assertTrue(result.get.height == photo1.height) // unchanged
        }.provide(seededLayer)
      },

      test("no-op patch (empty body) returns existing photo") {
        ZIO.serviceWithZIO[PhotoRepository](
          _.patch(1L, PhotoPatch())
        ).map(r => assertTrue(r.contains(photo1))).provide(seededLayer)
      }
    ),

    suite("delete")(
      test("returns true and removes the photo") {
        ZIO.serviceWithZIO[PhotoRepository] { repo =>
          for
            deleted <- repo.delete(1L)
            found   <- repo.findById(1L)
          yield assertTrue(deleted) && assertTrue(found.isEmpty)
        }.provide(seededLayer)
      },

      test("returns false for non-existent ID") {
        ZIO.serviceWithZIO[PhotoRepository](
          _.delete(9999L)
        ).map(r => assertTrue(!r)).provide(seededLayer)
      }
    ),

    suite("countByPhotographerId")(
      test("returns the correct count") {
        ZIO.serviceWithZIO[PhotoRepository](_.countByPhotographerId(100L))
          .map(n => assertTrue(n == 2L))
          .provide(seededLayer)
      }
    )
  )
