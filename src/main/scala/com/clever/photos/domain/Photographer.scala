package com.clever.photos.domain

import zio.json.*

/** A photographer record, representing the creator of one or more photos. */
final case class Photographer(
  photographerId: Long,
  name:           String,
  profileUrl:     String
)

object Photographer:
  given JsonCodec[Photographer] = DeriveJsonCodec.gen

/** Body required to create a photographer via POST /photographers. */
final case class PhotographerCreate(
  photographerId: Long,
  name:           String,
  profileUrl:     String
)

object PhotographerCreate:
  given JsonCodec[PhotographerCreate] = DeriveJsonCodec.gen

/** Full replacement body for PUT /photographers/:id. */
final case class PhotographerReplace(
  name:       String,
  profileUrl: String
)

object PhotographerReplace:
  given JsonCodec[PhotographerReplace] = DeriveJsonCodec.gen

/** Sparse update body for PATCH /photographers/:id. */
final case class PhotographerPatch(
  name:       Option[String] = None,
  profileUrl: Option[String] = None
)

object PhotographerPatch:
  given JsonCodec[PhotographerPatch] = DeriveJsonCodec.gen

/** Query parameters for GET /photographers. */
final case class PhotographerQuery(
  name:    Option[String] = None,
  page:    Int            = 1,
  perPage: Int            = 20
)
