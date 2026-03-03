#!/bin/bash
# Creates the least-privilege application role.
# Runs once when the PostgreSQL container is first initialised.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Create the app role with login (password injected from env)
    CREATE ROLE app_user WITH LOGIN PASSWORD '${APP_DB_PASSWORD}';

    -- Grant connection access
    GRANT CONNECT ON DATABASE photos_db TO app_user;
    -- USAGE + CREATE required: PostgreSQL 15+ removed the default CREATE grant
    -- on the public schema. Flyway (running as app_user) needs CREATE to build
    -- the schema history table and run migrations.
    GRANT USAGE, CREATE ON SCHEMA public TO app_user;

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

    -- Read-only role (for replica/reporting access); password injected from env
    CREATE ROLE readonly_user WITH LOGIN PASSWORD '${READONLY_DB_PASSWORD}';
    GRANT CONNECT ON DATABASE photos_db   TO readonly_user;
    GRANT USAGE   ON SCHEMA public        TO readonly_user;
    GRANT SELECT  ON ALL TABLES IN SCHEMA public TO readonly_user;
    -- FOR ROLE app_user: Flyway creates tables as app_user, so future tables
    -- are owned by app_user. This ensures readonly_user gets SELECT on them.
    ALTER DEFAULT PRIVILEGES FOR ROLE app_user IN SCHEMA public
        GRANT SELECT ON TABLES TO readonly_user;
EOSQL
