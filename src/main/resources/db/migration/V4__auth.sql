-- API clients for M2M (machine-to-machine) client-credentials OAuth flow.
-- The client_secret is NEVER stored in plaintext; only a BCrypt hash is kept.
-- scopes is a PostgreSQL text array, e.g. '{photos:read,photos:write}'.
CREATE TABLE api_clients (
    client_id    TEXT        PRIMARY KEY,
    secret_hash  TEXT        NOT NULL,
    name         TEXT        NOT NULL,
    scopes       TEXT[]      NOT NULL DEFAULT '{}',
    is_active    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index to speed up active-client lookups during token issuance.
CREATE INDEX idx_api_clients_active ON api_clients (client_id) WHERE is_active = TRUE;
