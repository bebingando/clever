package com.clever.photos.domain

import zio.json.*

/** A photo record as stored in the database.
  *
  * `base_image_url` holds the canonical Pexels JPEG URL. The various
  * sized src.* variants (large, medium, small, etc.) are NOT stored; they are
  * reconstructed at the API layer by appending query-string parameters to
  * `baseImageUrl`, saving ~400 bytes per row at the 1 M-photo scale.
  */
final case class Photo(
  id:             Long,
  photographerId: Long,
  width:          Int,
  height:         Int,
  pexelsUrl:      String,
  baseImageUrl:   String,
  avgColor:       String,
  alt:            Option[String]
)

object Photo:
  given JsonCodec[Photo] = DeriveJsonCodec.gen

/** Fields required to create a new photo. The `id` must be provided by the
  * caller (Pexels IDs are used as the primary key to preserve idempotency when
  * re-ingesting the dataset).
  */
final case class PhotoCreate(
  id:             Long,
  photographerId: Long,
  width:          Int,
  height:         Int,
  pexelsUrl:      String,
  baseImageUrl:   String,
  avgColor:       String,
  alt:            Option[String] = None
)

object PhotoCreate:
  given JsonCodec[PhotoCreate] = DeriveJsonCodec.gen

/** Full replacement body for PUT /photos/:id.  All fields are required. */
final case class PhotoReplace(
  photographerId: Long,
  width:          Int,
  height:         Int,
  pexelsUrl:      String,
  baseImageUrl:   String,
  avgColor:       String,
  alt:            Option[String]
)

object PhotoReplace:
  given JsonCodec[PhotoReplace] = DeriveJsonCodec.gen

/** Sparse update body for PATCH /photos/:id.
  * Only fields present in the JSON body are updated; absent fields are left
  * unchanged.  `alt` uses `Option[Option[String]]` to distinguish three states:
  *   - absent key           → `None`        (do not touch this field)
  *   - `"alt": null`        → `Some(None)`  (clear alt text to NULL)
  *   - `"alt": "some text"` → `Some(Some("some text"))` (set new value)
  * zio-json auto-derivation handles this correctly.
  */
final case class PhotoPatch(
  width:        Option[Int]            = None,
  height:       Option[Int]            = None,
  pexelsUrl:    Option[String]         = None,
  baseImageUrl: Option[String]         = None,
  avgColor:     Option[String]         = None,
  alt:          Option[Option[String]] = None
)

object PhotoPatch:
  given JsonCodec[PhotoPatch] = DeriveJsonCodec.gen

/** Query parameters supported by GET /photos. */
final case class PhotoQuery(
  photographerId: Option[Long]   = None,
  alt:            Option[String] = None,   // full-text keyword search
  width:          Option[Int]    = None,
  height:         Option[Int]    = None,
  minWidth:       Option[Int]    = None,
  minHeight:      Option[Int]    = None,
  avgColor:       Option[String] = None,
  page:           Int            = 1,
  perPage:        Int            = 20
)
