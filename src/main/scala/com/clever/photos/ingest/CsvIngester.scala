package com.clever.photos.ingest

import com.clever.photos.domain.*
import com.clever.photos.repository.{PhotoRepository, PhotographerRepository}
import zio.*
import zio.stream.*

import java.io.{BufferedReader, InputStreamReader}
import scala.io.Source

/** Ingests photo + photographer data from the bundled photos.csv on first boot.
  *
  * Design decisions:
  *  - The Pexels `id` field is used as the primary key (idempotent re-ingestion).
  *  - `src.*` URL variants are NOT stored; only `src.original` (base_image_url)
  *    is persisted. All variants are reconstructed at the API layer.
  *  - Photographers are upserted first (deduplication via ON CONFLICT DO NOTHING
  *    in the repository) so the FK constraint on photos is always satisfied.
  *  - Ingest runs only when the photos table is empty, so it is safe to leave
  *    enabled in production after the initial load.
  */
object CsvIngester:

  private val csvResource = "photos.csv"

  /** Entry point: reads the CSV, parses rows, inserts photographers then photos.
    * Idempotent — skips if photos already exist.
    */
  def ingest: ZIO[PhotoRepository & PhotographerRepository, Throwable, Unit] =
    for
      repo    <- ZIO.service[PhotoRepository]
      pgRepo  <- ZIO.service[PhotographerRepository]
      count   <- repo.findAll(com.clever.photos.domain.PhotoQuery(perPage = 1)).map(_._2)
      _       <- ZIO.when(count == 0)(loadAndInsert(pgRepo, repo))
    yield ()

  private def loadAndInsert(
    pgRepo:    PhotographerRepository,
    photoRepo: PhotoRepository
  ): ZIO[Any, Throwable, Unit] =
    for
      rows     <- ZIO.attempt(readCsv())
      _        <- ZIO.logInfo(s"Ingesting ${rows.size} photos from $csvResource")
      pgSet     = rows.map(_.photographer).distinctBy(_.photographerId)
      _        <- ZIO.foreachDiscard(pgSet)(p =>
                    pgRepo.create(p).catchSome {
                      case e: org.postgresql.util.PSQLException
                        if e.getMessage.contains("duplicate key") => ZIO.unit
                    }
                  )
      _        <- ZIO.foreachDiscard(rows)(r =>
                    photoRepo.create(r.photo).catchSome {
                      case e: org.postgresql.util.PSQLException
                        if e.getMessage.contains("duplicate key") => ZIO.unit
                    }
                  )
      _        <- ZIO.logInfo("Ingest complete")
    yield ()

  private final case class CsvRow(photo: Photo, photographer: Photographer)

  /** Reads photos.csv from the classpath. The CSV has 17 columns; see the
    * header row for the order. We use a minimal hand-rolled parser that
    * correctly handles quoted fields containing commas.
    */
  private def readCsv(): List[CsvRow] =
    val stream = getClass.getClassLoader.getResourceAsStream(csvResource)
    if stream == null then
      throw new RuntimeException(s"$csvResource not found on classpath")
    val reader  = new BufferedReader(new InputStreamReader(stream, "UTF-8"))
    try
      val lines = Iterator.continually(reader.readLine()).takeWhile(_ != null).toList
      lines.drop(1) // skip header
        .filter(_.nonEmpty)
        .flatMap(parseLine)
    finally
      reader.close()

  /** Parses a single CSV line, handling double-quoted fields.
    * Returns None and logs a warning for malformed rows.
    */
  private def parseLine(line: String): Option[CsvRow] =
    try
      val cols = splitCsvLine(line)
      // Column order (0-based):
      // 0=id, 1=width, 2=height, 3=url, 4=photographer, 5=photographer_url,
      // 6=photographer_id, 7=avg_color, 8=src.original, 9..15=other srcs, 16=alt
      if cols.length < 17 then None
      else
        val photoId        = cols(0).toLong
        val width          = cols(1).toInt
        val height         = cols(2).toInt
        val pexelsUrl      = cols(3)
        val photographerName = cols(4)
        val photographerUrl  = cols(5)
        val photographerId   = cols(6).toLong
        val avgColor         = cols(7)
        val baseImageUrl     = cols(8)
        val alt              = if cols(16).isBlank then None else Some(cols(16))

        val photographer = Photographer(
          photographerId = photographerId,
          name           = photographerName,
          profileUrl     = photographerUrl
        )
        val photo = Photo(
          id             = photoId,
          photographerId = photographerId,
          width          = width,
          height         = height,
          pexelsUrl      = pexelsUrl,
          baseImageUrl   = baseImageUrl,
          avgColor       = avgColor,
          alt            = alt
        )
        Some(CsvRow(photo, photographer))
    catch
      case e: Exception =>
        // Return None for unparseable rows; don't crash the whole ingest
        None

  /** Minimal RFC 4180-compliant CSV line splitter.
    * Handles fields that contain commas inside double quotes.
    */
  private def splitCsvLine(line: String): Vector[String] =
    val cols = scala.collection.mutable.ArrayBuffer.empty[String]
    val sb   = new StringBuilder
    var inQuote = false

    var i = 0
    while i < line.length do
      val c = line.charAt(i)
      if inQuote then
        if c == '"' then
          if i + 1 < line.length && line.charAt(i + 1) == '"' then
            // Escaped double-quote inside quoted field
            sb += '"'
            i += 1
          else
            inQuote = false
        else
          sb += c
      else
        if c == '"' then
          inQuote = true
        else if c == ',' then
          cols += sb.toString()
          sb.clear()
        else
          sb += c
      i += 1

    cols += sb.toString() // last field
    cols.toVector
