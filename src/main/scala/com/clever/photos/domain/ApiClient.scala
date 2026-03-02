package com.clever.photos.domain

import zio.json.*

/** A registered M2M (machine-to-machine) API client.
  *
  * Clients authenticate via the OAuth 2.0 Client Credentials flow:
  *   POST /auth/token  { client_id, client_secret, grant_type: "client_credentials" }
  *
  * The plain-text secret is NEVER stored. Only the BCrypt hash is persisted.
  * Scopes control what actions the client may perform (see [[TokenClaims]]).
  */
final case class ApiClient(
  clientId:   String,
  secretHash: String,
  name:       String,
  scopes:     List[String],
  isActive:   Boolean
)

/** The validated identity attached to every authenticated request.
  * Extracted from the JWT access token's payload after signature verification.
  */
final case class TokenClaims(
  clientId: String,
  scopes:   List[String]
):
  def hasScope(scope: String): Boolean = scopes.contains(scope)

object TokenClaims:
  given JsonCodec[TokenClaims] = DeriveJsonCodec.gen

/** The OAuth 2.0 token response returned by POST /auth/token. */
final case class TokenResponse(
  accessToken: String,
  tokenType:   String,
  expiresIn:   Long,
  scope:       String
)

object TokenResponse:
  given JsonCodec[TokenResponse] = DeriveJsonCodec.gen

/** Scope constants — define what each scope grants. */
object Scopes:
  val PhotosRead:         String = "photos:read"
  val PhotosWrite:        String = "photos:write"
  val PhotosDelete:       String = "photos:delete"
  val PhotographersWrite: String = "photographers:write"
  val Admin:              String = "admin"

  val all: List[String] = List(
    PhotosRead, PhotosWrite, PhotosDelete, PhotographersWrite, Admin
  )
