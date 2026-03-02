# Clever Photos API

A production-grade RESTful API for managing Pexels photo metadata, built with **Scala 3**, **ZIO 2**, **PostgreSQL 16**, and **Tapir**.

---

## Table of Contents

1. [Architecture Decisions and Trade-offs](#architecture-decisions-and-trade-offs)
2. [Features Implemented and Prioritisation](#features-implemented-and-prioritisation)
3. [How to Run the Application](#how-to-run-the-application)
4. [How to Run the Tests](#how-to-run-the-tests)
5. [API Reference](#api-reference)
6. [What I Would Add or Change With More Time](#what-i-would-add-or-change-with-more-time)
7. [Assumptions](#assumptions)

---

## Architecture Decisions and Trade-offs

### Database: PostgreSQL (Relational)

The dataset is a well-defined 1:N relationship (photographers → photos) with a stable schema. PostgreSQL was the clear choice over document stores (no schema flexibility required, ACID needed for writes) or wide-column stores (overkill for this access pattern).

**Schema normalisation decision:** The Pexels API returns 8 `src.*` URL variants per photo. All 8 are derivable from the base image URL by appending different query strings. Storing them would cost roughly 400 bytes × 1 M rows ≈ 400 MB of redundant data. They are therefore **not stored**; the API reconstructs them on demand from `base_image_url`.

**Indexing strategy:**

| Index | Type | Supports |
|---|---|---|
| `photos.id` (PK) | B-tree | `GET/PUT/PATCH/DELETE /photos/:id` |
| `photos.photographer_id` | B-tree | `GET /photographers/:id/photos`, FK lookups |
| `photographers.name` | B-tree | Future name-search endpoint |
| `photos.alt` | GIN (tsvector) | `GET /photos?alt=keyword` full-text search |
| `photos.(width, height)` | B-tree composite | `GET /photos?min_width=...&min_height=...` |

### Application: Scala 3 + ZIO 2

ZIO's effect system makes the following architectural qualities explicit in types:
- **Dependency injection** via `ZLayer` — every service declares its dependencies; no hidden globals.
- **Resource safety** via `Scope` — the HikariCP connection pool is guaranteed to close on shutdown.
- **Concurrency** — ZIO fibres are cheap and the thread pool is automatically tuned.

**Trade-off:** The ZIO stack has a steeper learning curve than a synchronous framework. It pays off at scale or in teams already familiar with functional Scala.

### HTTP + OpenAPI: Tapir

Tapir defines endpoints as **typed values** rather than routes. This means the OpenAPI spec is generated from the same source of truth as the server logic — it cannot get out of sync.

**Trade-off:** More ceremony than direct ZIO-HTTP for simple CRUD, but the type safety and auto-generated docs justify it here.

### Database Access: Doobie

- SQL is explicit — no ORM magic hiding N+1 queries.
- Full-text search (`to_tsvector`/`to_tsquery`) is trivial in raw SQL; mapping it through an ORM is awkward.
- Works naturally with ZIO via `zio-interop-cats`.

### Authentication: JWT via OAuth 2.0 Client Credentials Flow

This is a B2B/M2M API with no human users assumed. The Client Credentials flow is the standard choice:

1. A machine client POSTs its `client_id` and `client_secret` to `POST /auth/token`.
2. The server returns a short-lived JWT (1 hour by default).
3. All subsequent requests carry `Authorization: Bearer <token>`.

**Why JWT over opaque tokens?** JWTs are stateless — the server verifies the signature without a DB lookup on every request. This matters for throughput at scale.

**Security layers:**

| Layer | Implementation |
|---|---|
| Network | Docker bridge network — PostgreSQL never exposed to the host |
| DB authentication | `pg_hba.conf` with `scram-sha-256` |
| DB authorisation | Least-privilege `app_user` role (DML only, no DDL) |
| App authentication | JWT HMAC-SHA256, configurable expiry |
| App authorisation | Scope-based: `photos:read`, `photos:write`, `photos:delete`, `photographers:write`, `admin` |
| Secret storage | Client secrets stored as BCrypt hashes (never plaintext) |

**Auth service is a mockable interface:** `LiveAuthService` uses real JWT + BCrypt; `MockAuthService` accepts any non-empty token and grants all scopes. Switch via `AUTH_MOCK_MODE=true`. No code changes required — only the `ZLayer` wired in `Main.scala` changes.

---

## Features Implemented and Prioritisation

### Must-haves (all implemented)

- ✅ Ingest and store `photos.csv` on first boot (idempotent)
- ✅ Full CRUD for `/photos` and `/photographers`
- ✅ `GET /photographers/:id/photos` nested route
- ✅ `GET /photos?alt=keyword` full-text search (PostgreSQL GIN index)
- ✅ Filtering by `photographer_id`, `width`, `height`, `min_width`, `min_height`, `avg_color`
- ✅ Pagination (`page`, `per_page`) on all list endpoints
- ✅ JWT / OAuth 2.0 Client Credentials authentication
- ✅ Scope-based authorisation
- ✅ Mockable/injectable auth service
- ✅ Swagger UI served at `/docs` (auto-generated from endpoint definitions)
- ✅ Correct HTTP status codes (200, 201, 204, 400, 401, 403, 404, 409, 422, 500)
- ✅ `409 Conflict` with clear message when deleting a photographer with existing photos
- ✅ `avgColor` format validation (`^#[0-9A-Fa-f]{6}$`)
- ✅ PostgreSQL security: `pg_hba.conf`, least-privilege role, private Docker network
- ✅ HikariCP connection pool with configurable size
- ✅ Flyway database migrations (versioned, idempotent)
- ✅ Docker Compose + multi-stage `Dockerfile`
- ✅ ZIO-test suite: unit tests for auth, repository logic, API validation

### Nice-to-haves (documented below)

- ⬜ Read-only PostgreSQL replica
- ⬜ TLS on the PostgreSQL connection
- ⬜ Rate limiting
- ⬜ Kubernetes manifests

---

## How to Run the Application

### Prerequisites

- Docker Desktop (or Docker Engine + Compose v2)
- JDK 21 (only needed to run tests without Docker)

### Quick start

```bash
# 1. Clone and enter the repository
git clone <repo-url>
cd clever

# 2. Create a .env file (never commit this)
cat > .env <<'EOF'
POSTGRES_PASSWORD=choose_a_strong_superuser_password
APP_DB_PASSWORD=choose_a_strong_app_password
JWT_SECRET=choose_a_long_random_secret
DEFAULT_CLIENT_ID=dev-client
DEFAULT_CLIENT_SECRET=choose_a_client_secret
EOF

# 3. Build and start all services (first build takes a few minutes)
docker compose up --build
```

The API is ready when you see:
```
INFO  c.c.p.Main - Starting server on 0.0.0.0:8080
```

### What happens on first boot

1. Flyway runs the SQL migrations (`V1` – `V4`).
2. A default API client is created using `DEFAULT_CLIENT_ID` / `DEFAULT_CLIENT_SECRET`.
3. The 10-photo CSV dataset is ingested.
4. The HTTP server starts on port 8080.

### Get an access token

```bash
curl -s -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{
    "clientId":     "dev-client",
    "clientSecret": "choose_a_client_secret",
    "grantType":    "client_credentials"
  }' | jq .
```

Response:
```json
{
  "accessToken": "eyJ...",
  "tokenType":   "Bearer",
  "expiresIn":   3600,
  "scope":       "photos:read photos:write photos:delete photographers:write admin"
}
```

### Call the API

```bash
TOKEN="eyJ..."

# List all photos
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/photos" | jq .

# Full-text search
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/photos?alt=island" | jq .

# Get a single photo
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/photos/21751820" | jq .

# Photos by photographer
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/photographers/57767809/photos" | jq .
```

### Swagger UI

Open **http://localhost:8080/docs** in a browser for interactive documentation.

---

## How to Run the Tests

Tests use `zio-test` exclusively and require **no database** — all repository dependencies are replaced with in-memory stubs.

```bash
# Requires JDK 21 and sbt
sbt test

# Run a specific suite
sbt "testOnly com.clever.photos.AuthServiceSpec"
sbt "testOnly com.clever.photos.PhotoRepositorySpec"
sbt "testOnly com.clever.photos.PhotoApiSpec"
```

### Test coverage summary

| Suite | What it tests |
|---|---|
| `AuthServiceSpec` | JWT issuance, validation, tamper detection, scope enforcement, MockAuthService |
| `PhotoRepositorySpec` | CRUD, filtering, pagination, full-text search (in-memory stub) |
| `PhotoApiSpec` | API-layer validation (avgColor format, scope checks, 409 logic) |

---

## API Reference

### Authentication

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/auth/token` | None | Issue a JWT via client credentials |

### Photos

| Method | Path | Required Scope | Description |
|---|---|---|---|
| GET | `/photos` | `photos:read` | List / search photos |
| GET | `/photos/:id` | `photos:read` | Get a photo by ID |
| POST | `/photos` | `photos:write` | Create a photo |
| PUT | `/photos/:id` | `photos:write` | Replace a photo (all fields) |
| PATCH | `/photos/:id` | `photos:write` | Partially update a photo |
| DELETE | `/photos/:id` | `photos:delete` | Delete a photo |

### Photographers

| Method | Path | Required Scope | Description |
|---|---|---|---|
| GET | `/photographers` | `photos:read` | List photographers |
| GET | `/photographers/:id` | `photos:read` | Get a photographer |
| GET | `/photographers/:id/photos` | `photos:read` | Photos by photographer |
| POST | `/photographers` | `photographers:write` | Create a photographer |
| PUT | `/photographers/:id` | `photographers:write` | Replace a photographer |
| PATCH | `/photographers/:id` | `photographers:write` | Partially update |
| DELETE | `/photographers/:id` | `admin` | Delete (409 if photos exist) |

### Query parameters for `GET /photos`

| Parameter | Type | Description |
|---|---|---|
| `photographer_id` | integer | Filter by photographer |
| `alt` | string | Full-text keyword search (stemmed, AND-joined) |
| `width` | integer | Exact width |
| `height` | integer | Exact height |
| `min_width` | integer | Minimum width |
| `min_height` | integer | Minimum height |
| `avg_color` | string | Exact hex match, URL-encoded (e.g. `%23333831`) |
| `page` | integer | Page number, 1-based (default: 1) |
| `per_page` | integer | Items per page, max 100 (default: 20) |

### HTTP Status Codes

| Code | When |
|---|---|
| 200 | Successful GET, PUT, PATCH |
| 201 | Successful POST |
| 204 | Successful DELETE |
| 400 | Malformed request |
| 401 | Missing or invalid JWT |
| 403 | JWT valid but insufficient scope |
| 404 | Resource not found |
| 409 | FK conflict (e.g. deleting photographer with photos) |
| 422 | Valid JSON but failed business validation (e.g. negative dimensions) |
| 500 | Unexpected server error |

---

## What I Would Add or Change With More Time

### High priority

1. **Integration tests with Testcontainers** — spin up a real PostgreSQL instance per test run and exercise the actual Doobie queries and GIN index behaviour. The in-memory stubs cannot catch SQL bugs.

2. **Rate limiting** — apply per-IP and per-client limits, with stricter limits on `?alt=` (GIN queries are more expensive than PK lookups).

3. **TLS on PostgreSQL** — use `hostssl` in `pg_hba.conf`. Currently omitted because both services run on the same Docker network, but mandatory for cross-host deployments.

4. **Refresh tokens** — the current access tokens cannot be revoked before expiry. A refresh-token table with rotation-on-use and replay detection would add revocation capability.

### Medium priority

5. **Prometheus metrics** — `/metrics` with request latency histograms, pool utilisation, and DB query durations.

6. **Read-only PostgreSQL replica** — route all `SELECT` queries to a streaming replica via a separate `Transactor`. The repository interface is already abstracted; this is additive.

7. **`/admin/clients` endpoint** — currently the only way to create API clients is via the first-boot seed. A protected admin endpoint would make client management self-service.

8. **Kubernetes manifests** — `Deployment`, `Service`, `ConfigMap`, and `Secret` objects.

### Polish

9. **`PUT` upsert semantics** — RFC 9110 allows `PUT` to create a resource if it does not exist. Currently returns `404` for unknown IDs.

10. **Structured logging** — enrich log entries with `request_id`, `client_id`, and `duration_ms`.

---

## Assumptions

1. **Photo IDs come from Pexels and are the primary key.** The API requires callers to supply `id` on `POST /photos`. In a fully user-generated system the server would generate IDs.

2. **Reads require authentication.** This provides an audit trail and prevents anonymous abuse of the full-text search endpoint (consistent with Pexels' own API policy).

3. **No photographer ownership of photos.** This is a B2B/M2M API. Any client with `photos:write` scope can modify any photo. Adding ownership would require a `users` → `photographers` link and a human auth flow, which is out of scope for M2M.

4. **`avg_color` format is validated by the API, not the DB.** The `CHAR(7)` column silently accepts any 7-character string; validation is enforced in the application with a regex.

5. **The CSV ingest is a one-time operation.** It runs only when the `photos` table is empty. Subsequent restarts skip it.

6. **Development secrets are acceptable in `.env`.** For production, inject from AWS Secrets Manager, HashiCorp Vault, or similar. The `.env` file is in `.gitignore`.
