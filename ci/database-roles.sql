-- SPDX-License-Identifier: AGPL-3.0-only
-- The database roles of a deployment, as the Helm chart creates them (docs/tenancy.md), for CI's smoke test and local
-- development (docker-compose.yml): an owner that runs `ostrom-server migrate`, and a serving role that owns nothing
-- and cannot bypass row-level security. The passwords are local placeholders, never a deployment's.
CREATE ROLE ostrom_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD 'ostrom_owner';
CREATE ROLE ostrom_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD 'ostrom_app';
CREATE DATABASE ostrom OWNER ostrom_owner;
\connect ostrom
ALTER SCHEMA public OWNER TO ostrom_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ostrom_app;
ALTER DEFAULT PRIVILEGES FOR ROLE ostrom_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ostrom_app;
ALTER DEFAULT PRIVILEGES FOR ROLE ostrom_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO ostrom_app;
ALTER DEFAULT PRIVILEGES FOR ROLE ostrom_owner IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO ostrom_app;
