// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.artifacts.infrastructure

import dev.ostrom.server.kernel.database.ROUTER_ROLE
import dev.ostrom.server.kernel.database.ROUTER_ROLE_SQL
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.database.routedTableSql
import dev.ostrom.server.kernel.database.routerFunctionsSql

/** Hands the cross-organization functions of version 2 over to [ROUTER_ROLE]. */
private val ROUTE_FUNCTIONS_OWNER = routerFunctionsSql("artifacts_route_by_id(UUID)", "artifacts_route_by_slug(TEXT)")

/**
 * The schema of the artifacts module; its tables start with `artifacts_`, and each is confined by row-level security
 * to the organization of the transaction (S2-01, S2-51). `artifacts_routes` (an artifact's id, organization and link
 * slug) is also reached across organizations, only through the narrow `SECURITY DEFINER` functions of version 2, owned
 * by [ROUTER_ROLE]: the content origin finds an artifact by its signed id or its public link.
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
        name = "confine_artifact_routes",
        sql = """
            $ROUTER_ROLE_SQL
            ${routedTableSql("artifacts_routes", "SELECT")}
            CREATE FUNCTION artifacts_route_by_id(p_artifact_id UUID) RETURNS TABLE (artifact_id UUID, org_id UUID)
                LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public
                AS $$ SELECT r.artifact_id, r.org_id FROM artifacts_routes r WHERE r.artifact_id = p_artifact_id $$;
            CREATE FUNCTION artifacts_route_by_slug(p_slug TEXT) RETURNS TABLE (artifact_id UUID, org_id UUID)
                LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public
                AS $$ SELECT r.artifact_id, r.org_id FROM artifacts_routes r WHERE r.link_slug = p_slug $$;
            $ROUTE_FUNCTIONS_OWNER
        """.trimIndent(),
    ),
)
