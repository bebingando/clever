package com.clever.photos.repository

import com.clever.photos.domain.*
import cats.syntax.apply.*
import doobie.*
import doobie.implicits.*
import zio.*
import zio.interop.catz.*

trait PhotographerRepository:
  def findById(id: Long): Task[Option[Photographer]]
  def findAll(query: PhotographerQuery): Task[(List[Photographer], Long)]
  def create(p: Photographer): Task[Photographer]
  def replace(id: Long, r: PhotographerReplace): Task[Option[Photographer]]
  def patch(id: Long, p: PhotographerPatch): Task[Option[Photographer]]
  def delete(id: Long): Task[Boolean]
  def existsById(id: Long): Task[Boolean]

final class LivePhotographerRepository(xa: Transactor[Task]) extends PhotographerRepository:

  def findById(id: Long): Task[Option[Photographer]] =
    sql"SELECT photographer_id, name, profile_url FROM photographers WHERE photographer_id = $id"
      .query[Photographer]
      .option
      .transact(xa)

  def findAll(q: PhotographerQuery): Task[(List[Photographer], Long)] =
    val base    = fr"FROM photographers WHERE 1=1"
    val filters = q.name.map(n => fr"AND name ILIKE ${"%" + n + "%"}").toList
    val where   = filters.foldLeft(base)(_ ++ _)
    val offset  = (q.page - 1) * q.perPage

    val dataQ =
      (fr"SELECT photographer_id, name, profile_url " ++ where ++
        fr"ORDER BY photographer_id LIMIT ${q.perPage} OFFSET $offset")
        .query[Photographer]
        .to[List]

    val countQ = (fr"SELECT count(*) " ++ where).query[Long].unique

    (for rows <- dataQ; total <- countQ yield (rows, total)).transact(xa)

  def create(p: Photographer): Task[Photographer] =
    sql"""INSERT INTO photographers (photographer_id, name, profile_url)
          VALUES (${p.photographerId}, ${p.name}, ${p.profileUrl})"""
      .update
      .run
      .transact(xa)
      .as(p)

  def replace(id: Long, r: PhotographerReplace): Task[Option[Photographer]] =
    sql"""UPDATE photographers SET name = ${r.name}, profile_url = ${r.profileUrl}
          WHERE photographer_id = $id"""
      .update
      .run
      .transact(xa)
      .flatMap {
        case 0 => ZIO.succeed(None)
        case _ => findById(id)
      }

  def patch(id: Long, p: PhotographerPatch): Task[Option[Photographer]] =
    val sets = List(
      p.name.map(v       => fr"name = $v"),
      p.profileUrl.map(v => fr"profile_url = $v")
    ).flatten

    if sets.isEmpty then findById(id)
    else
      val setClause = sets.reduceLeft(_ ++ fr"," ++ _)
      (fr"UPDATE photographers SET " ++ setClause ++ fr" WHERE photographer_id = $id")
        .update
        .run
        .transact(xa)
        .flatMap {
          case 0 => ZIO.succeed(None)
          case _ => findById(id)
        }

  def delete(id: Long): Task[Boolean] =
    sql"DELETE FROM photographers WHERE photographer_id = $id"
      .update
      .run
      .transact(xa)
      .map(_ > 0)

  def existsById(id: Long): Task[Boolean] =
    sql"SELECT EXISTS(SELECT 1 FROM photographers WHERE photographer_id = $id)"
      .query[Boolean]
      .unique
      .transact(xa)

object LivePhotographerRepository:
  // Doobie auto-derives Read[Photographer]; no explicit given needed.

  val layer: URLayer[Transactor[Task], PhotographerRepository] =
    ZLayer.fromFunction(new LivePhotographerRepository(_))
