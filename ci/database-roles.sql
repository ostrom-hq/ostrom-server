-- SPDX-License-Identifier: AGPL-3.0-only
-- The database roles of a deployment, as the Helm chart creates them (docs/tenancy.md), for CI's smoke test and local
-- development (docker-compose.yml): an owner that runs `ostrom-server migrate`, and a serving role that owns nothing
-- and cannot bypass row-level security. The passwords are local placeholders, never a deployment's.
-- The owner has CREATEROLE because some modules' migrations create `ostrom_tenant` when it is missing (their
-- `inTenant`); since PostgreSQL 16 that only lets it manage the roles it administers, never a superuser or BYPASSRLS.
CREATE ROLE ostrom_owner LOGIN NOSUPERUSER NOCREATEDB CREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD 'ostrom_owner';
CREATE ROLE ostrom_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD 'ostrom_app';
-- The role those modules' queries switch to (SET LOCAL ROLE): owner of nothing, so the policies apply to it; the
-- serving role must be a member of it.
-- Roles are global to the cluster: tolerate one created before (CI's tests create it in the same server).
DO $$ BEGIN CREATE ROLE ostrom_tenant NOLOGIN NOSUPERUSER NOBYPASSRLS; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
GRANT ostrom_tenant TO ostrom_owner WITH ADMIN OPTION;
GRANT ostrom_tenant TO ostrom_app;
-- The owner of the cross-organization functions (docs/tenancy.md): owner of no table, never granted to the serving
-- role. The owner administers it to hand its functions over; tolerate one created before, like ostrom_tenant.
DO $$ BEGIN CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
GRANT ostrom_router TO ostrom_owner WITH ADMIN OPTION;
-- The owner of the member search's function (orgs migration 7), the same way: never the serving role's.
DO $$ BEGIN CREATE ROLE ostrom_member_search NOLOGIN NOSUPERUSER NOBYPASSRLS; EXCEPTION WHEN duplicate_object THEN NULL; END $$;
GRANT ostrom_member_search TO ostrom_owner WITH ADMIN OPTION;
CREATE DATABASE ostrom OWNER ostrom_owner;
\connect ostrom
ALTER SCHEMA public OWNER TO ostrom_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ostrom_app, ostrom_tenant;
-- Every table, the migrations' history `_migrations_<module>` included: `ostrom-server migrate` then revokes every
-- write on the history from every role but the owner (MigrationRunnerImpl), so the serving role only reads it.
ALTER DEFAULT PRIVILEGES FOR ROLE ostrom_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ostrom_app;
ALTER DEFAULT PRIVILEGES FOR ROLE ostrom_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO ostrom_app;
ALTER DEFAULT PRIVILEGES FOR ROLE ostrom_owner IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO ostrom_app;
