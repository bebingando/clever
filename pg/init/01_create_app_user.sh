#!/bin/bash
# Creates the least-privilege application role.
# Runs once when the PostgreSQL container is first initialised.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Create the app role with login (password injected from env)
    CREATE ROLE app_user WITH LOGIN PASSWORD '${APP_DB_PASSWORD}';

    -- Grant connection access
    GRANT CONNECT ON DATABASE photos_db TO app_user;
    GRANT USAGE   ON SCHEMA public     TO app_user;

    -- Row-level DML only — no DDL, no superuser
    GRANT SELECT, INSERT, UPDATE, DELETE
        ON ALL TABLES    IN SCHEMA public TO app_user;
    GRANT USAGE, SELECT
        ON ALL SEQUENCES IN SCHEMA public TO app_user;

    -- Ensure future tables (created by Flyway at startup) get the same grants
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES    TO app_user;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT USAGE, SELECT                  ON SEQUENCES TO app_user;

    -- Read-only role placeholder (for future replica support)
    CREATE ROLE readonly_user WITH LOGIN PASSWORD 'readonly_placeholder';
    GRANT CONNECT ON DATABASE photos_db   TO readonly_user;
    GRANT USAGE   ON SCHEMA public        TO readonly_user;
    GRANT SELECT  ON ALL TABLES IN SCHEMA public TO readonly_user;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public
        GRANT SELECT ON TABLES TO readonly_user;
EOSQL
