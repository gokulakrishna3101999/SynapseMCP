#!/bin/bash
# Runs once, automatically, the first time the postgres container's data volume is empty
# (official postgres image convention: /docker-entrypoint-initdb.d/*.sh, alphabetical order).
# Creates the non-owner synapse_app role the app connects as, so RLS is actually enforced - the same
# setup docs/local-db-setup.sql describes for a non-Docker local machine. Flyway's own migrations (run
# by the app on startup, as $POSTGRES_USER, the schema owner) create the extensions/tables/RLS policies;
# this script only needs to exist before that.
set -e

: "${SYNAPSE_APP_PASSWORD:?SYNAPSE_APP_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-SQL
    CREATE ROLE synapse_app LOGIN PASSWORD '$SYNAPSE_APP_PASSWORD' NOSUPERUSER NOCREATEDB NOCREATEROLE;
    GRANT CONNECT ON DATABASE $POSTGRES_DB TO synapse_app;
    GRANT USAGE ON SCHEMA public TO synapse_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO synapse_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO synapse_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO synapse_app;
SQL
