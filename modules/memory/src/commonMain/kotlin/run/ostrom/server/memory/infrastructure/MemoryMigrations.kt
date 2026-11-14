// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

/** The cross-organization read of the settling pass, a function owned by `ostrom_router`. */
private const val SETTLING_ORGS = "memory_settling_orgs(UUID, INTEGER)"

/**
 * Version 2: an agent's organization entry is a proposal (TeamCode #272), bound to the wait that asks its session's
 * person (`proposal_wait_id`). The settling pass reads by status, oldest first (`memory_entries_status`), and finds
 * the organizations holding proposals or rejected entries through `memory_settling_orgs`, owned by `ostrom_router`
 * (docs/tenancy.md), which returns their ids only; each is then settled in its own organization.
 */
private val PROPOSALS_SQL: String =
    """
    ALTER TABLE memory_entries ADD COLUMN proposal_wait_id UUID;
    CREATE INDEX memory_entries_status ON memory_entries (org_id, status, updated_at, id);
    DO $$ BEGIN
        CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_router TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    GRANT USAGE ON SCHEMA public TO ostrom_router;
    CREATE POLICY memory_entries_router ON memory_entries FOR SELECT TO ostrom_router
        USING (current_user = 'ostrom_router');
    GRANT SELECT ON memory_entries TO ostrom_router;
    CREATE FUNCTION memory_settling_orgs(p_after UUID, p_limit INTEGER) RETURNS TABLE (org_id UUID)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT DISTINCT e.org_id FROM memory_entries e WHERE e.status IN ('proposed', 'rejected')
            AND (p_after IS NULL OR e.org_id > p_after) ORDER BY e.org_id LIMIT p_limit;
    $$;
    REVOKE EXECUTE ON FUNCTION $SETTLING_ORGS FROM PUBLIC;
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            GRANT EXECUTE ON FUNCTION $SETTLING_ORGS TO ostrom_app;
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ALTER FUNCTION $SETTLING_ORGS OWNER TO ostrom_router;
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

/** The cross-organization read of the curation run, a function owned by `ostrom_router`. */
private const val KEPT_ORGS = "memory_kept_orgs(UUID, INTEGER)"

/**
 * Version 3: the curation (TeamCode #273). An entry curation superseded names the one that replaced it
 * (`superseded_by_id`); `memory_settings` holds an organization's setting (off without a row) and what its last run
 * did; `memory_curation_state` when each scope was last curated; `memory_reviews` the pairs a person decides, repeating
 * the older entry's scope and owner so a queue is one index range. The run finds the organizations holding entries
 * through `memory_kept_orgs`, owned by `ostrom_router` (docs/tenancy.md): their ids only, by a loose scan of the
 * entries' leading `org_id`, so a run reads one index entry per organization.
 */
private val CURATION_SQL: String =
    """
    ALTER TABLE memory_entries ADD COLUMN superseded_by_id UUID;
    CREATE TABLE memory_settings (
        org_id UUID PRIMARY KEY,
        curation_enabled BOOLEAN NOT NULL DEFAULT FALSE,
        last_run_at TIMESTAMPTZ,
        last_run_id TEXT,
        last_superseded INTEGER NOT NULL DEFAULT 0,
        last_queued INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE memory_curation_state (
        org_id UUID NOT NULL,
        scope TEXT NOT NULL,
        owner_id TEXT NOT NULL DEFAULT '',
        curated_at TIMESTAMPTZ NOT NULL,
        PRIMARY KEY (org_id, scope, owner_id)
    );
    CREATE TABLE memory_reviews (
        id UUID PRIMARY KEY,
        org_id UUID NOT NULL,
        scope TEXT NOT NULL,
        owner_id TEXT NOT NULL DEFAULT '',
        older_id UUID NOT NULL REFERENCES memory_entries (id) ON DELETE CASCADE,
        newer_id UUID NOT NULL REFERENCES memory_entries (id) ON DELETE CASCADE,
        kind TEXT NOT NULL,
        reason TEXT NOT NULL,
        status TEXT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
        resolved_at TIMESTAMPTZ
    );
    CREATE UNIQUE INDEX memory_reviews_pair ON memory_reviews (older_id, newer_id);
    CREATE INDEX memory_reviews_newer ON memory_reviews (newer_id);
    CREATE INDEX memory_reviews_queue ON memory_reviews (org_id, status, scope, owner_id, created_at);
    CREATE INDEX memory_reviews_closed ON memory_reviews (org_id, status, resolved_at);
    ${tenantTableSql("memory_settings")}
    ${tenantTableSql("memory_curation_state")}
    ${tenantTableSql("memory_reviews")}
    CREATE FUNCTION memory_kept_orgs(p_after UUID, p_limit INTEGER) RETURNS TABLE (org_id UUID)
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        WITH RECURSIVE kept (id) AS (
            SELECT (SELECT min(e.org_id) FROM memory_entries e WHERE p_after IS NULL OR e.org_id > p_after)
            UNION ALL
            SELECT (SELECT min(e.org_id) FROM memory_entries e WHERE e.org_id > kept.id)
                FROM kept WHERE kept.id IS NOT NULL
        )
        SELECT kept.id FROM kept WHERE kept.id IS NOT NULL LIMIT p_limit;
    $$;
    REVOKE EXECUTE ON FUNCTION $KEPT_ORGS FROM PUBLIC;
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            GRANT EXECUTE ON FUNCTION $KEPT_ORGS TO ostrom_app;
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ALTER FUNCTION $KEPT_ORGS OWNER TO ostrom_router;
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

/**
 * The schema of the memory module, shaped as TeamCode's `MemoryTables.kt` (`memories`, `memory_tags`), each table
 * confined by row-level security to the organization of the transaction (S2-01):
 * - `memory_entries`: one row per entry; `owner_id` is the member of a personal entry and empty for the organization's,
 *   so one index (`org_id, scope, owner_id, status, updated_at`) serves both scopes' lists, which never read `content`;
 * - `memory_tags`: an entry's tags, repeating its organization, scope and owner (they never change), so a tag filter is
 *   one covering index range of the caller's own scope.
 */
val MemoryMigrations: List<SchemaMigration> = listOf(
    SchemaMigration(
        version = 1,
        name = "create_memory",
        sql = """
            CREATE TABLE memory_entries (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL,
                scope TEXT NOT NULL,
                owner_id TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                content TEXT NOT NULL,
                refs TEXT NOT NULL,
                status TEXT NOT NULL,
                origin TEXT NOT NULL,
                author_id TEXT NOT NULL,
                session_id TEXT,
                supersedes_id UUID,
                expires_at TIMESTAMPTZ,
                version INTEGER NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                last_read_at TIMESTAMPTZ,
                read_count INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX memory_entries_visible ON memory_entries (org_id, scope, owner_id, status, updated_at);
            CREATE TABLE memory_tags (
                memory_id UUID NOT NULL REFERENCES memory_entries (id) ON DELETE CASCADE,
                tag TEXT NOT NULL,
                org_id UUID NOT NULL,
                scope TEXT NOT NULL,
                owner_id TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (memory_id, tag)
            );
            CREATE INDEX memory_tags_lookup ON memory_tags (org_id, scope, owner_id, tag, memory_id);
            $TENANT_ROLE_SQL
            ${tenantTableSql("memory_entries")}
            ${tenantTableSql("memory_tags")}
        """.trimIndent(),
    ),
    SchemaMigration(
        version = 2,
        name = "memory_proposals",
        sql = PROPOSALS_SQL,
    ),
    SchemaMigration(
        version = 3,
        name = "memory_curation",
        sql = CURATION_SQL,
    ),
)
