-- SynapseMCP local database bootstrap (development only).
-- Run once as a PostgreSQL superuser (e.g. `admin`), connected to the `postgres` database:
--   psql -h localhost -U admin -d postgres -f docs/local-db-setup.sql
-- Requires PostgreSQL 17 with the pgvector extension files installed on the server.
--
-- Two roles are used on purpose:
--   * the migration role (superuser/schema owner) runs Flyway and owns the tables
--   * `synapse_app` is a NON-owner, non-superuser role the application connects as,
--     so PostgreSQL Row-Level Security is actually enforced against it.
-- Replace the placeholder password before running.

CREATE ROLE synapse_app LOGIN PASSWORD 'change-me' NOSUPERUSER NOCREATEDB NOCREATEROLE;

CREATE DATABASE synapsemcp;

\connect synapsemcp

GRANT CONNECT ON DATABASE synapsemcp TO synapse_app;
GRANT USAGE ON SCHEMA public TO synapse_app;

-- Tables and functions are created later by Flyway running as the migration role.
-- These default privileges give `synapse_app` DML on every table it creates (RLS still filters rows)
-- and EXECUTE on functions such as authenticate_api_key().
-- NOTE: they apply to objects created by the role that runs this script, so run it as the same
-- role you use for DB_MIGRATION_USERNAME. If they differ, repeat with
--   ALTER DEFAULT PRIVILEGES FOR ROLE <migration_role> IN SCHEMA public ...
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO synapse_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO synapse_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO synapse_app;

-- Flyway V1 runs CREATE EXTENSION vector / "uuid-ossp", which needs superuser (or a trusted-extension owner).
