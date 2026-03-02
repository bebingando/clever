package com.clever.photos.repository

import com.clever.photos.domain.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import zio.*
import zio.interop.catz.*

/** Data-access interface for photos.
  * All methods are pure ZIO effects; no IO leaks out.
  */
trait PhotoRepository:
  def findById(id: Long): Task[Option[Photo]]
  def findAll(query: PhotoQuery): Task[(List[Photo], Long)]
  def findByPhotographerId(photographerId: Long, page: Int, perPage: Int): Task[(List[Photo], Long)]
  def create(photo: Photo): Task[Photo]
  def replace(id: Long, r: PhotoReplace): Task[Option[Photo]]
  def patch(id: Long, p: PhotoPatch): Task[Option[Photo]]
  def delete(id: Long): Task[Boolean]
  def countByPhotographerId(photographerId: Long): Task[Long]
  def existsById(id: Long): Task[Boolean]

object PhotoRepository:
  // Accessor methods — delegate to the service in the ZIO environment
  def findById(id: Long): ZIO[PhotoRepository, Throwable, Option[Photo]] =
    ZIO.serviceWithZIO(_.findById(id))
  def findAll(q: PhotoQuery): ZIO[PhotoRepository, Throwable, (List[Photo], Long)] =
    ZIO.serviceWithZIO(_.findAll(q))
  def create(p: Photo): ZIO[PhotoRepository, Throwable, Photo] =
    ZIO.serviceWithZIO(_.create(p))

/** Live implementation backed by PostgreSQL via Doobie. */
final class LivePhotoRepository(xa: Transactor[Task]) extends PhotoRepository:

  import LivePhotoRepository.PhotoRead

  def findById(id: Long): Task[Option[Photo]] =
    sql"""SELECT id, photographer_id, width, height, pexels_url,
                 base_image_url, avg_color, alt
            FROM photos WHERE id = $id"""
      .query[Photo]
      .option
      .transact(xa)

  def findAll(q: PhotoQuery): Task[(List[Photo], Long)] =
    val base = fr"""FROM photos WHERE 1=1"""

    val filters = List(
      q.photographerId.map(id => fr"AND photographer_id = $id"),
      q.width.map(w         => fr"AND width = $w"),
      q.height.map(h        => fr"AND height = $h"),
      q.minWidth.map(w      => fr"AND width >= $w"),
      q.minHeight.map(h     => fr"AND height >= $h"),
      q.avgColor.map(c      => fr"AND avg_color = $c"),
      q.alt.map(kw => {
        val tsQuery = kw.trim.split("\\s+").mkString(" & ")
        fr"AND to_tsvector('english', coalesce(alt, '')) @@ to_tsquery('english', $tsQuery)"
      })
    ).flatten

    val whereClause = filters.foldLeft(base)(_ ++ _)
    val offset      = (q.page - 1) * q.perPage

    val dataQ =
      (fr"""SELECT id, photographer_id, width, height, pexels_url,
                   base_image_url, avg_color, alt """ ++ whereClause ++
        fr"ORDER BY id LIMIT ${q.perPage} OFFSET $offset")
        .query[Photo]
        .to[List]

    val countQ =
      (fr"SELECT count(*) " ++ whereClause)
        .query[Long]
        .unique

    (dataQ, countQ).mapN((rows, total) => (rows, total)).transact(xa)

  def findByPhotographerId(photographerId: Long, page: Int, perPage: Int): Task[(List[Photo], Long)] =
    findAll(PhotoQuery(photographerId = Some(photographerId), page = page, perPage = perPage))

  def create(photo: Photo): Task[Photo] =
    sql"""INSERT INTO photos
            (id, photographer_id, width, height, pexels_url, base_image_url, avg_color, alt)
          VALUES
            (${photo.id}, ${photo.photographerId}, ${photo.width}, ${photo.height},
             ${photo.pexelsUrl}, ${photo.baseImageUrl}, ${photo.avgColor}, ${photo.alt})"""
      .update
      .run
      .transact(xa)
      .as(photo)

  def replace(id: Long, r: PhotoReplace): Task[Option[Photo]] =
    sql"""UPDATE photos
            SET photographer_id = ${r.photographerId},
                width           = ${r.width},
                height          = ${r.height},
                pexels_url      = ${r.pexelsUrl},
                base_image_url  = ${r.baseImageUrl},
                avg_color       = ${r.avgColor},
                alt             = ${r.alt}
          WHERE id = $id"""
      .update
      .run
      .transact(xa)
      .flatMap {
        case 0 => ZIO.succeed(None)
        case _ => findById(id)
      }

  def patch(id: Long, p: PhotoPatch): Task[Option[Photo]] =
    // Build the SET clause from only the fields that were provided
    val sets = List(
      p.width.map(v        => fr"width = $v"),
      p.height.map(v       => fr"height = $v"),
      p.pexelsUrl.map(v    => fr"pexels_url = $v"),
      p.baseImageUrl.map(v => fr"base_image_url = $v"),
      p.avgColor.map(v     => fr"avg_color = $v"),
      p.alt.map(v          => fr"alt = $v")
    ).flatten

    if sets.isEmpty then findById(id)
    else
      val setClause = sets.reduceLeft(_ ++ fr"," ++ _)
      (fr"UPDATE photos SET " ++ setClause ++ fr" WHERE id = $id")
        .update
        .run
        .transact(xa)
        .flatMap {
          case 0 => ZIO.succeed(None)
          case _ => findById(id)
        }

  def delete(id: Long): Task[Boolean] =
    sql"DELETE FROM photos WHERE id = $id"
      .update
      .run
      .transact(xa)
      .map(_ > 0)

  def countByPhotographerId(photographerId: Long): Task[Long] =
    sql"SELECT count(*) FROM photos WHERE photographer_id = $photographerId"
      .query[Long]
      .unique
      .transact(xa)

  def existsById(id: Long): Task[Boolean] =
    sql"SELECT EXISTS(SELECT 1 FROM photos WHERE id = $id)"
      .query[Boolean]
      .unique
      .transact(xa)

object LivePhotoRepository:
  // Doobie derives Read[Photo] automatically from the field types.
  given PhotoRead: Read[Photo] = Read.derived

  val layer: URLayer[Transactor[Task], PhotoRepository] =
    ZLayer.fromFunction(new LivePhotoRepository(_))
