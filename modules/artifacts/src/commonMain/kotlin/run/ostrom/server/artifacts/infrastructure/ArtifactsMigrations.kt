// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

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

/**
 * The organizations with expired uploads, expired artifacts, drafts created before `p_drafts`, publications made
 * before `p_publications`, or contents marked for collection, each found on its own index. Frozen: migration 12 is
 * written from it.
 */
internal fun dueOrganizationsSql(now: String, drafts: String, publications: String, limit: String): String =
    "SELECT DISTINCT d.org_id FROM (SELECT u.org_id FROM artifacts_uploads u WHERE u.expires_at <= $now " +
        "UNION ALL SELECT a.org_id FROM artifacts_artifacts a WHERE a.expires_at <= $now " +
        "UNION ALL SELECT a.org_id FROM artifacts_artifacts a WHERE a.latest_version = 0 AND a.created_at < $drafts " +
        "UNION ALL SELECT p.org_id FROM artifacts_publications p WHERE p.published_at < $publications " +
        "UNION ALL SELECT b.org_id FROM artifacts_blobs b WHERE b.marked_at IS NOT NULL) d LIMIT $limit"

private val MAINTAINED_TABLES =
    listOf("artifacts_uploads", "artifacts_artifacts", "artifacts_publications", "artifacts_blobs")

private const val DUE_FUNCTION = "artifacts_due_organizations(TIMESTAMPTZ, TIMESTAMPTZ, TIMESTAMPTZ, INTEGER)"

/** Version 12: the indexes of the maintenance and its cross-organization function, owned by `ostrom_router`. */
private val MAINTENANCE_SQL: String =
    """
    CREATE INDEX artifacts_uploads_expiry ON artifacts_uploads (expires_at);
    CREATE INDEX artifacts_artifacts_draft_age ON artifacts_artifacts (created_at) WHERE latest_version = 0;
    CREATE INDEX artifacts_publications_age ON artifacts_publications (published_at);
    ${MAINTAINED_TABLES.joinToString("\n") {
        "CREATE POLICY ${it}_router ON $it FOR SELECT TO ostrom_router USING (current_user = 'ostrom_router');\n" +
            "GRANT SELECT ON $it TO ostrom_router;"
    }}
    CREATE FUNCTION artifacts_due_organizations(p_now TIMESTAMPTZ, p_drafts TIMESTAMPTZ, p_publications TIMESTAMPTZ,
        p_limit INTEGER) RETURNS SETOF UUID
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        ${dueOrganizationsSql("p_now", "p_drafts", "p_publications", "p_limit")};
    $$;
    REVOKE EXECUTE ON FUNCTION $DUE_FUNCTION FROM PUBLIC;
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            GRANT EXECUTE ON FUNCTION $DUE_FUNCTION TO ostrom_app;
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ALTER FUNCTION $DUE_FUNCTION OWNER TO ostrom_router;
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

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
    // RLS audit R9: the router's policies hold only inside its own functions, as identity's
    // do; the owner, a member of `ostrom_router` to hand them over, no longer passes them.
    SchemaMigration(
        version = 3,
        name = "artifacts_router_policies_inside_functions_only",
        sql = """
            ALTER POLICY artifacts_routes_router ON artifacts_routes USING (current_user = 'ostrom_router')
        """.trimIndent(),
    ),
    // TeamCode's `artifact_storage` and `artifact_settings`: one row per organization, the bucket's secret sealed
    // with the instance's secrets key (context `artifact-storage:<org>`), under the same row-level security.
    SchemaMigration(
        version = 4,
        name = "artifacts_storage_and_settings",
        sql = """
            CREATE TABLE artifacts_storage (
                org_id UUID PRIMARY KEY,
                endpoint TEXT NOT NULL,
                region TEXT NOT NULL,
                bucket TEXT NOT NULL,
                prefix TEXT NOT NULL,
                path_style BOOLEAN NOT NULL,
                access_key_id TEXT NOT NULL,
                secret_sealed TEXT NOT NULL,
                server_side_encryption TEXT NOT NULL,
                verified_at TIMESTAMPTZ,
                version INTEGER NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL
            );
            CREATE TABLE artifacts_settings (
                org_id UUID PRIMARY KEY,
                enabled BOOLEAN NOT NULL,
                who_can_publish TEXT NOT NULL,
                public_sharing TEXT NOT NULL,
                quota_member_bytes BIGINT NOT NULL,
                quota_org_bytes BIGINT NOT NULL,
                quota_artifacts_per_member INTEGER NOT NULL,
                quota_publishes_per_hour INTEGER NOT NULL,
                personal_retention_days INTEGER,
                shared_retention_days INTEGER,
                max_versions_per_artifact INTEGER NOT NULL,
                version INTEGER NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL
            );
            ${tenantTableSql("artifacts_storage")}
            ${tenantTableSql("artifacts_settings")}
        """.trimIndent(),
    ),
    // The bytes leave PostgreSQL for the organization's bucket: each content keeps its size here; the bytes of those
    // published before stay until an administrator turns artifacts on, which moves them (verified by SHA-256) and
    // forgets them. A later migration drops the column.
    SchemaMigration(
        version = 5,
        name = "artifacts_blobs_sizes",
        sql = """
            ALTER TABLE artifacts_blobs ADD COLUMN size BIGINT;
            ALTER TABLE artifacts_blobs NO FORCE ROW LEVEL SECURITY;
            UPDATE artifacts_blobs SET size = octet_length(bytes);
            ALTER TABLE artifacts_blobs FORCE ROW LEVEL SECURITY;
            ALTER TABLE artifacts_blobs ALTER COLUMN size SET NOT NULL;
            ALTER TABLE artifacts_blobs ALTER COLUMN bytes DROP NOT NULL;
        """.trimIndent(),
    ),
    // TeamCode's uploads, without a byte: the chunks wait in the organization's bucket, these rows say which arrived
    // so that any replica takes the next one. A draft is an artifact whose latest version is 0.
    SchemaMigration(
        version = 6,
        name = "artifacts_uploads",
        sql = """
            CREATE TABLE artifacts_uploads (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                artifact_id UUID NOT NULL,
                member_id TEXT NOT NULL,
                credential_id TEXT,
                created_at TIMESTAMPTZ NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                UNIQUE (id, org_id),
                FOREIGN KEY (artifact_id, org_id) REFERENCES artifacts_artifacts (id, org_id) ON DELETE CASCADE
            );
            CREATE INDEX artifacts_uploads_member ON artifacts_uploads (org_id, member_id, expires_at);
            CREATE INDEX artifacts_uploads_artifact ON artifacts_uploads (artifact_id);
            CREATE TABLE artifacts_upload_blobs (
                upload_id UUID NOT NULL,
                org_id UUID NOT NULL,
                sha256 TEXT NOT NULL,
                size BIGINT NOT NULL,
                state TEXT NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY (upload_id, sha256),
                FOREIGN KEY (upload_id, org_id) REFERENCES artifacts_uploads (id, org_id) ON DELETE CASCADE
            );
            CREATE TABLE artifacts_upload_chunks (
                upload_id UUID NOT NULL,
                org_id UUID NOT NULL,
                sha256 TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                PRIMARY KEY (upload_id, sha256, chunk_index),
                FOREIGN KEY (upload_id, sha256) REFERENCES artifacts_upload_blobs (upload_id, sha256) ON DELETE CASCADE
            );
            CREATE INDEX artifacts_artifacts_drafts ON artifacts_artifacts (org_id, owner_id) WHERE latest_version = 0;
            ${tenantTableSql("artifacts_uploads")}
            ${tenantTableSql("artifacts_upload_blobs")}
            ${tenantTableSql("artifacts_upload_chunks")}
        """.trimIndent(),
    ),
    // A version committed from an upload keeps its manifest's digest, the version it started from and the credential
    // that committed it: the same commit within ten minutes answers that version again (TeamCode's `replayed`).
    SchemaMigration(
        version = 7,
        name = "artifacts_versions_commits",
        sql = """
            ALTER TABLE artifacts_versions ADD COLUMN digest TEXT;
            ALTER TABLE artifacts_versions ADD COLUMN base_version INTEGER;
            ALTER TABLE artifacts_versions ADD COLUMN credential_id TEXT;
        """.trimIndent(),
    ),
    // No file byte is kept in PostgreSQL: they are in the organization's bucket, and artifacts are off without one.
    // Ostrom had no organization in production when the column went, so nothing published before it is moved.
    SchemaMigration(
        version = 8,
        name = "artifacts_blobs_without_bytes",
        sql = "ALTER TABLE artifacts_blobs DROP COLUMN bytes;",
    ),
    // Each publication of a version, for `publishesPerHour`: a pruned version still counts, as TeamCode counts them
    // from its audit log. The maintenance forgets those past the hour.
    SchemaMigration(
        version = 9,
        name = "artifacts_publications",
        sql = """
            CREATE TABLE artifacts_publications (
                org_id UUID NOT NULL,
                member_id TEXT NOT NULL,
                artifact_id UUID NOT NULL,
                published_at TIMESTAMPTZ NOT NULL
            );
            CREATE INDEX artifacts_publications_member ON artifacts_publications (org_id, member_id, published_at);
            ${tenantTableSql("artifacts_publications")}
        """.trimIndent(),
    ),
    // The quotas read on each publication: the folders' bytes by organization, an owner's published artifacts.
    SchemaMigration(
        version = 10,
        name = "artifacts_usage_indexes",
        sql = """
            CREATE INDEX artifacts_blobs_org ON artifacts_blobs (org_id) INCLUDE (artifact_id, size);
            CREATE INDEX artifacts_artifacts_owner ON artifacts_artifacts (org_id, owner_id) WHERE latest_version > 0;
        """.trimIndent(),
    ),
    // TeamCode's retention: an artifact expires from its last publication or access change, and a content no kept
    // version uses is marked for the maintenance, which deletes it from the bucket (a later version that uses it
    // again clears the mark).
    SchemaMigration(
        version = 11,
        name = "artifacts_retention",
        sql = """
            ALTER TABLE artifacts_artifacts ADD COLUMN expires_at TIMESTAMPTZ;
            ALTER TABLE artifacts_blobs ADD COLUMN marked_at TIMESTAMPTZ;
            CREATE INDEX artifacts_artifacts_expiry ON artifacts_artifacts (expires_at) WHERE expires_at IS NOT NULL;
            CREATE INDEX artifacts_blobs_marked ON artifacts_blobs (marked_at) WHERE marked_at IS NOT NULL;
        """.trimIndent(),
    ),
    // The maintenance (TeamCode's ArtifactMaintenance) finds the organizations with work due through one narrow
    // function of the router, then works in each under row-level security.
    SchemaMigration(version = 12, name = "artifacts_maintenance", sql = MAINTENANCE_SQL),
)
