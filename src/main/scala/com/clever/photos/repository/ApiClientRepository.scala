package com.clever.photos.repository

import com.clever.photos.domain.ApiClient
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import zio.*
import zio.interop.catz.*

trait ApiClientRepository:
  def findById(clientId: String): Task[Option[ApiClient]]
  def create(client: ApiClient): Task[ApiClient]
  def countAll(): Task[Long]

final class LiveApiClientRepository(xa: Transactor[Task]) extends ApiClientRepository:

  def findById(clientId: String): Task[Option[ApiClient]] =
    sql"""SELECT client_id, secret_hash, name, scopes, is_active
            FROM api_clients
           WHERE client_id = $clientId AND is_active = TRUE"""
      .query[ApiClient]
      .option
      .transact(xa)

  def create(client: ApiClient): Task[ApiClient] =
    sql"""INSERT INTO api_clients (client_id, secret_hash, name, scopes, is_active)
          VALUES (${client.clientId}, ${client.secretHash}, ${client.name},
                  ${client.scopes.toArray}, ${client.isActive})
          ON CONFLICT (client_id) DO NOTHING"""
      .update
      .run
      .transact(xa)
      .as(client)

  def countAll(): Task[Long] =
    sql"SELECT count(*) FROM api_clients WHERE is_active = TRUE"
      .query[Long]
      .unique
      .transact(xa)

object LiveApiClientRepository:
  // Doobie's postgres implicits provide Get[Array[String]] for text[].
  // We need an explicit Read[ApiClient] that bridges Array[String] → List[String]
  // because Read.derived would look for Get[List[String]] which doesn't exist
  // in the postgres module (only Array variants are provided).
  given Read[ApiClient] =
    Read[(String, String, String, Array[String], Boolean)].map {
      case (clientId, secretHash, name, scopes, isActive) =>
        ApiClient(clientId, secretHash, name, scopes.toList, isActive)
    }

  val layer: URLayer[Transactor[Task], ApiClientRepository] =
    ZLayer.fromFunction(new LiveApiClientRepository(_))
