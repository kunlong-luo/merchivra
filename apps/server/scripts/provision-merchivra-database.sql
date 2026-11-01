-- Merchivra ONLY: one-time PostgreSQL 18 cluster bootstrap (NOT a Flyway migration).
-- For EXPLICIT manual execution by an authorized administrator on an approved
-- cluster with a separate existing application workload such as spark-db.
-- NEVER run from bootRun, CI, Docker/Compose startup or application setup.
--
-- PURE PostgreSQL SQL for IntelliJ IDEA / DataGrip / pgAdmin / DBeaver / psql.
-- There are NO executable psql backslash commands in this file.
--
-- In graphical clients: connect as an authorized PostgreSQL SUPERUSER to
-- maintenance database postgres; use AUTOCOMMIT EACH STATEMENT, STOP ON ERROR,
-- SEQUENTIAL execution. Never use Run in single transaction/BEGIN or run
-- in parallel: PostgreSQL CREATE DATABASE cannot run inside a transaction.
-- DBeaver script mode: SQL Processing > Scripts > Commit type = After each query,
-- Error handling = Stop. Toolbar autocommit alone may not change script mode.
--
-- Manual shell invocation, after carefully inspecting target cluster:
-- docker exec -i spark-postgres psql -X -v ON_ERROR_STOP=1 \
--   -U root -d postgres < apps/server/scripts/provision-merchivra-database.sql
--
-- Do NOT add -1/--single-transaction. The command must be run from repo root.
-- The role begins NOLOGIN; password must be set securely OUTSIDE this script,
-- through a protected GUI role/password dialog or interactive psql password
-- prompt, BEFORE the administrator separately executes ALTER ROLE ... LOGIN.
--
-- Intentional fail-closed policy: any existing merchivra role OR database
-- causes an error and no further statements should run. Inspect pg_roles and
-- pg_database, rather than automatically repairing, altering, dropping, or
-- changing ownership of anything on an interrupted or preexisting cluster.
--
-- NEVER target iot_farm or any other application database.
-- NEVER store passwords, connection URIs containing credentials or tokens here.

DO $merchivra_preflight$
BEGIN
    IF current_database() <> 'postgres' THEN
        RAISE EXCEPTION
            'Refusing Merchivra cluster provisioning outside maintenance database postgres';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_roles
        WHERE rolname = current_user
          AND rolsuper
    ) THEN
        RAISE EXCEPTION
            'Merchivra provisioning requires an explicitly authorized cluster superuser';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'merchivra') THEN
        RAISE EXCEPTION
            'Role merchivra already exists; inspect ownership and privileges manually';
    END IF;

    IF EXISTS (SELECT 1 FROM pg_database WHERE datname = 'merchivra') THEN
        RAISE EXCEPTION
            'Database merchivra already exists; inspect it before any changes';
    END IF;
END
$merchivra_preflight$;

CREATE ROLE merchivra
    NOLOGIN
    NOSUPERUSER
    NOCREATEDB
    NOCREATEROLE
    NOREPLICATION;

CREATE DATABASE merchivra OWNER merchivra;

-- Remove default database-level CONNECT/TEMP/CREATE privileges for PUBLIC;
-- the database owner retains its implicit owner privileges.
REVOKE ALL ON DATABASE merchivra FROM PUBLIC;

-- DONE. Do not enable LOGIN here or configure a password via SQL in Git.
--
-- AFTER inspecting success:
-- 1) Confirm cluster password_encryption uses scram-sha-256.
-- 2) As an authorized administrator, assign a unique strong password using
--    GUI's protected password dialog OR interactive psql's password command.
-- 3) Only after step 2, manually execute this separate SQL statement:
--      ALTER ROLE merchivra LOGIN;
-- 4) Test password-authenticated TCP to jdbc:postgresql://127.0.0.1:5432/merchivra
--    using the dedicated merchivra user; configure DB_PASSWORD for bootRun/IDE.
--    Docker Compose reads MERCHIVRA_DB_PASSWORD from a protected local .env.
--
-- Flyway creates/updates the schema INSIDE the existing merchivra database.
-- PostgreSQL database creation is NEVER the application's responsibility.
