// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.artifacts.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/**
 * The schema of the artifacts module; its tables start with `artifacts_`, and each is confined by row-level security
 * to the organization of the transaction (S2-01). `artifacts_routes` (an artifact's id, organization and link slug,
 * no customer content) is also read across organizations by the content origin, which finds an artifact by its signed
 * id or its public link: only through [ROUTER_FUNCTIONS] (version 2, docs/tenancy.md).
 */
val ArtifactsMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_artifacts",
        sql = """
            CREATE TABLE artifacts_artifacts (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                owner_id TEXT NOT NULL,
                title TEXT NOT NULL,
                access TEXT NOT NULL,
                latest_version INTEGER NOT NULL,
                link_slug TEXT UNIQUE,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                UNIQUE (id, org_id)
            );
            CREATE INDEX artifacts_artifacts_org_updated ON artifacts_artifacts (org_id, updated_at DESC, id DESC);
            CREATE TABLE artifacts_versions (
                artifact_id UUID NOT NULL,
                org_id UUID NOT NULL,
                number INTEGER NOT NULL,
                entry TEXT NOT NULL,
                files TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                created_by TEXT NOT NULL,
                PRIMARY KEY (artifact_id, number),
                FOREIGN KEY (artifact_id, org_id) REFERENCES artifacts_artifacts (id, org_id) ON DELETE CASCADE
            );
            CREATE TABLE artifacts_blobs (
                artifact_id UUID NOT NULL,
                org_id UUID NOT NULL,
                sha256 TEXT NOT NULL,
                bytes BYTEA NOT NULL,
                PRIMARY KEY (artifact_id, sha256),
                FOREIGN KEY (artifact_id, org_id) REFERENCES artifacts_artifacts (id, org_id) ON DELETE CASCADE
            );
            CREATE TABLE artifacts_members (
                artifact_id UUID NOT NULL,
                org_id UUID NOT NULL,
                member_id TEXT NOT NULL,
                PRIMARY KEY (artifact_id, member_id),
                FOREIGN KEY (artifact_id, org_id) REFERENCES artifacts_artifacts (id, org_id) ON DELETE CASCADE
            );
            CREATE INDEX artifacts_members_member ON artifacts_members (org_id, member_id);
            CREATE TABLE artifacts_routes (
                artifact_id UUID PRIMARY KEY REFERENCES artifacts_artifacts (id) ON DELETE CASCADE,
                org_id UUID NOT NULL,
                link_slug TEXT UNIQUE
            );
            $TENANT_ROLE_SQL
            ${tenantTableSql("artifacts_artifacts")}
            ${tenantTableSql("artifacts_versions")}
            ${tenantTableSql("artifacts_blobs")}
            ${tenantTableSql("artifacts_members")}
            GRANT SELECT, INSERT, UPDATE ON artifacts_routes TO $TENANT_ROLE;
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 2,
        name = "artifacts_routes_under_row_level_security",
        sql = ROUTES_UNDER_ROW_LEVEL_SECURITY_SQL,
    ),
)

/** The cross-organization reads of the content origin, `SECURITY DEFINER` functions owned by `ostrom_router`. */
private val ROUTER_FUNCTIONS = listOf("artifacts_route_by_id(UUID)", "artifacts_route_by_slug(TEXT)")

/**
 * Version 2: `artifacts_routes` under forced row-level security like the other tables (a request writes only its
 * organization's route), and its two cross-organization reads moved into narrow functions owned by `ostrom_router`
 * (`NOLOGIN`, never a member of the serving role), with `pg_temp` last in their `search_path`, executable by the
 * serving role only (RLS audit R2 and R3).
 */
private val ROUTES_UNDER_ROW_LEVEL_SECURITY_SQL: String =
    """
    ALTER TABLE artifacts_routes ENABLE ROW LEVEL SECURITY;
    ALTER TABLE artifacts_routes FORCE ROW LEVEL SECURITY;
    CREATE POLICY artifacts_routes_tenant ON artifacts_routes
        USING (org_id = CAST(NULLIF(current_setting('$TENANT_SETTING', true), '') AS UUID))
        WITH CHECK (org_id = CAST(NULLIF(current_setting('$TENANT_SETTING', true), '') AS UUID));
    DO $$ BEGIN
        CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_router TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    GRANT USAGE ON SCHEMA public TO ostrom_router;
    CREATE POLICY artifacts_routes_router ON artifacts_routes FOR SELECT TO ostrom_router USING (true);
    GRANT SELECT ON artifacts_routes TO ostrom_router;
    CREATE FUNCTION artifacts_route_by_id(p_id UUID) RETURNS TABLE (artifact_id UUID, org_id UUID)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT r.artifact_id, r.org_id FROM artifacts_routes r WHERE r.artifact_id = p_id;
    $$;
    CREATE FUNCTION artifacts_route_by_slug(p_slug TEXT) RETURNS TABLE (artifact_id UUID, org_id UUID)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT r.artifact_id, r.org_id FROM artifacts_routes r WHERE r.link_slug = p_slug;
    $$;
    ${ROUTER_FUNCTIONS.joinToString("\n") { "REVOKE EXECUTE ON FUNCTION $it FROM PUBLIC;" }}
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            ${ROUTER_FUNCTIONS.joinToString("\n            ") { "GRANT EXECUTE ON FUNCTION $it TO ostrom_app;" }}
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ${ROUTER_FUNCTIONS.joinToString("\n") { "ALTER FUNCTION $it OWNER TO ostrom_router;" }}
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()
