// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs.infrastructure

import run.ostrom.server.kernel.database.SchemaMigration

/** The functions' signatures, for their handover to `ostrom_router`. */
private val ROUTER_FUNCTIONS =
    listOf(
        "orgs_create_organization(UUID, TEXT, TEXT, BIGINT, TEXT, TIMESTAMPTZ, UUID, UUID, TEXT, TIMESTAMPTZ)",
        "orgs_claim_holders(TEXT)",
        "orgs_find_by_slug(TEXT)",
        "orgs_slug_taken(TEXT)",
        "orgs_memberships_of(UUID)",
        "orgs_live_invitation(TEXT, TIMESTAMPTZ)",
    )

/**
 * The cross-organization paths of the module, as narrow `SECURITY DEFINER` functions owned by
 * `ostrom_router` (`NOLOGIN`, never a member of the serving role, granted only these tables through a
 * policy of its own). Each takes exactly what its operation needs. Only the serving role may run them (audit S2-60):
 * `EXECUTE` is revoked from `PUBLIC` and granted to `ostrom_app` when the deployment has created it (the Helm chart
 * and `ci/database-roles.sql` create it before `migrate`), and `pg_temp` comes last in their `search_path`, so a
 * temporary object can never stand in for one of theirs.
 */
private val CROSS_ORGANIZATION_SQL: String =
    """
    DO $$ BEGIN
        CREATE ROLE ostrom_router NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_router TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    GRANT USAGE ON SCHEMA public TO ostrom_router;
    CREATE POLICY orgs_organizations_router ON orgs_organizations TO ostrom_router USING (true) WITH CHECK (true);
    GRANT SELECT, INSERT ON orgs_organizations TO ostrom_router;
    CREATE POLICY orgs_members_router ON orgs_members TO ostrom_router USING (true) WITH CHECK (true);
    GRANT SELECT, INSERT ON orgs_members TO ostrom_router;
    CREATE POLICY orgs_invitations_router ON orgs_invitations TO ostrom_router USING (true);
    GRANT SELECT ON orgs_invitations TO ostrom_router;
    CREATE FUNCTION orgs_create_organization(
        p_id UUID, p_slug TEXT, p_name TEXT, p_github BIGINT, p_claim TEXT, p_created TIMESTAMPTZ,
        p_member UUID, p_user UUID, p_role TEXT, p_joined TIMESTAMPTZ
    ) RETURNS VOID LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        INSERT INTO orgs_organizations (id, slug, name, github_org_id, free_claim, created_at)
            VALUES (p_id, p_slug, p_name, p_github, p_claim, p_created);
        INSERT INTO orgs_members (id, org_id, user_id, role, joined_at)
            VALUES (p_member, p_id, p_user, p_role, p_joined);
    $$;
    CREATE FUNCTION orgs_claim_holders(p_claim TEXT) RETURNS SETOF orgs_organizations
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT * FROM orgs_organizations WHERE free_claim = p_claim AND deleted_at IS NULL;
    $$;
    CREATE FUNCTION orgs_find_by_slug(p_slug TEXT) RETURNS SETOF orgs_organizations
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT * FROM orgs_organizations WHERE slug = p_slug AND deleted_at IS NULL;
    $$;
    CREATE FUNCTION orgs_slug_taken(p_slug TEXT) RETURNS BOOLEAN
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT EXISTS (SELECT 1 FROM orgs_organizations WHERE slug = p_slug);
    $$;
    CREATE FUNCTION orgs_memberships_of(p_user UUID) RETURNS TABLE (
        id UUID, slug TEXT, name TEXT, github_org_id BIGINT, free_claim TEXT, created_at TIMESTAMPTZ,
        member_id UUID, org_id UUID, user_id UUID, role TEXT, joined_at TIMESTAMPTZ
    ) LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT o.id, o.slug, o.name, o.github_org_id, o.free_claim, o.created_at,
               m.id, m.org_id, m.user_id, m.role, m.joined_at
        FROM orgs_members m JOIN orgs_organizations o ON o.id = m.org_id
        WHERE m.user_id = p_user AND o.deleted_at IS NULL;
    $$;
    CREATE FUNCTION orgs_live_invitation(p_hash TEXT, p_now TIMESTAMPTZ) RETURNS SETOF orgs_invitations
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT i.* FROM orgs_invitations i
        WHERE i.token_hash = p_hash AND i.revoked_at IS NULL AND i.expires_at > p_now
          AND (i.max_uses IS NULL OR i.uses < i.max_uses)
          AND EXISTS (SELECT 1 FROM orgs_organizations o WHERE o.id = i.org_id AND o.deleted_at IS NULL);
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
 * The most people a prefix may name for the search to read their memberships by key; beyond, it walks the team in
 * order, where such a prefix matches often and fills a page early.
 */
private const val MEMBER_SEARCH_CANDIDATES = 500

/** The member search's signature, for its handover to `ostrom_member_search`. */
private const val MEMBER_SEARCH_FUNCTION = "orgs_search_members(UUID, TEXT, UUID, INTEGER)"

/**
 * Version 7: the member search (`GET /orgs/{org}/members?q=`) on a team of 5 000 or more, under its indexes.
 *
 * The login and the name live in `identity_users`, which the tenant role does not read: a `SECURITY DEFINER` function
 * owned by `ostrom_member_search` (`NOLOGIN`, no bypass of the row level security, a policy of its own on
 * `orgs_members` bound to the session's organization, `SELECT` on three columns of `identity_users`: `id`, `login`,
 * `display_name`) joins them. It refuses an organization that is not the session's (`ostrom.org_id`), escapes the
 * prefix's own wildcards, and returns only member ids, in the members' order, after a member, at most a page. Only
 * `ostrom_tenant` may run it. It first reads, from the `text_pattern_ops` indexes alone (they carry `id`), up to 501
 * people the prefix names: a rare prefix then reads their memberships by key, a common one walks the team in order and
 * stops at the page (left to choose, the planner walked the team for `user0-4`: CI run 41695887597, p95 64 ms; with
 * 1 001 read from the table, `q=u` spent 5 ms choosing: run 41702166113; OrgsReadsBenchTest).
 */
private val MEMBER_SEARCH_SQL: String =
    """
    CREATE INDEX orgs_members_by_join ON orgs_members (org_id, joined_at, id);
    CREATE INDEX identity_users_login_prefix ON identity_users (lower(login) text_pattern_ops) INCLUDE (id);
    CREATE INDEX identity_users_name_prefix ON identity_users (lower(display_name) text_pattern_ops) INCLUDE (id);
    DO $$ BEGIN
        CREATE ROLE ostrom_member_search NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN NULL;
    END $$;
    DO $$ BEGIN
        GRANT ostrom_member_search TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN NULL;
    END $$;
    GRANT USAGE ON SCHEMA public TO ostrom_member_search;
    CREATE POLICY orgs_members_search ON orgs_members FOR SELECT TO ostrom_member_search
        USING (current_user = 'ostrom_member_search'
            AND org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID));
    GRANT SELECT ON orgs_members TO ostrom_member_search;
    GRANT SELECT (id, login, display_name) ON identity_users TO ostrom_member_search;
    CREATE FUNCTION orgs_search_members(p_org UUID, p_prefix TEXT, p_after UUID, p_limit INTEGER)
        RETURNS TABLE (member_id UUID)
        LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
    DECLARE
        pattern TEXT := replace(replace(replace(lower(p_prefix), '\', '\\'), '%', '\%'), '_', '\_') || '%';
        candidates UUID[];
    BEGIN
        IF p_org IS DISTINCT FROM CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID) THEN
            RAISE EXCEPTION 'orgs_search_members: not the session''s organization' USING ERRCODE = '42501';
        END IF;
        -- The people the prefix names, at most $MEMBER_SEARCH_CANDIDATES + 1: each index walked in its own order
        -- (`USING ~<~`, text_pattern_ops) from the prefix, so a common prefix stops there too, never a table read.
        EXECUTE 'SELECT ARRAY(SELECT c.id FROM ('
            || '(SELECT u.id FROM identity_users u WHERE lower(u.login) LIKE $1 '
            || 'ORDER BY lower(u.login) USING ~<~ LIMIT $2) UNION '
            || '(SELECT u.id FROM identity_users u WHERE lower(u.display_name) LIKE $1 '
            || 'ORDER BY lower(u.display_name) USING ~<~ LIMIT $2)) c LIMIT $2)'
            INTO candidates USING pattern, $MEMBER_SEARCH_CANDIDATES + 1;
        IF cardinality(candidates) <= $MEMBER_SEARCH_CANDIDATES THEN
            -- A rare prefix: those people's memberships here, by the (org_id, user_id) key.
            RETURN QUERY SELECT m.id FROM orgs_members m
                WHERE m.org_id = p_org AND m.user_id = ANY (candidates)
                AND (p_after IS NULL OR (m.joined_at, m.id) >
                    (SELECT a.joined_at, a.id FROM orgs_members a WHERE a.org_id = p_org AND a.id = p_after))
                ORDER BY m.joined_at, m.id LIMIT p_limit;
        ELSE
            -- A common prefix: the team in order, each member's person by primary key, until the page is full.
            RETURN QUERY EXECUTE
                'SELECT m.id FROM orgs_members m CROSS JOIN LATERAL (SELECT 1 FROM identity_users u '
                || 'WHERE u.id = m.user_id AND (lower(u.login) LIKE $2 OR lower(u.display_name) LIKE $2) LIMIT 1) p '
                || 'WHERE m.org_id = $1 AND ($3 IS NULL OR (m.joined_at, m.id) > '
                || '(SELECT a.joined_at, a.id FROM orgs_members a WHERE a.org_id = $1 AND a.id = $3)) '
                || 'ORDER BY m.joined_at, m.id LIMIT $4'
                USING p_org, pattern, p_after, p_limit;
        END IF;
    END;
    $$;
    REVOKE EXECUTE ON FUNCTION $MEMBER_SEARCH_FUNCTION FROM PUBLIC;
    GRANT EXECUTE ON FUNCTION $MEMBER_SEARCH_FUNCTION TO ostrom_tenant;
    GRANT CREATE ON SCHEMA public TO ostrom_member_search;
    ALTER FUNCTION $MEMBER_SEARCH_FUNCTION OWNER TO ostrom_member_search;
    REVOKE CREATE ON SCHEMA public FROM ostrom_member_search;
    """
        .trimIndent()

/** The functions of version 3, for their handover to `ostrom_router`. */
private val DELETION_FUNCTIONS =
    listOf(
        "orgs_memberships_of(UUID)",
        "orgs_deletions_to_settle(TIMESTAMPTZ, INTEGER)",
        "orgs_due_purges(TIMESTAMPTZ, INTEGER)",
    )

/**
 * Version 3: the time zone of an organization, and the end of a deletion. A deleted organization
 * keeps its row and its data for 30 days (`purge_after`, set too for the organizations deleted
 * before this version); the worker tells every module of the deletion (`deletion_settled_at`),
 * then erases it (`purged_at`); a step a module failed on waits until `deletion_retry_at`. Finding them crosses
 * organizations, so two `SECURITY DEFINER` functions owned by `ostrom_router` list their ids, and
 * nothing else; `orgs_memberships_of` is made again to return the time zone.
 */
private val SETTINGS_AND_DELETION_SQL: String =
    """
    ALTER TABLE orgs_organizations
        ADD COLUMN time_zone TEXT NOT NULL DEFAULT 'UTC',
        ADD COLUMN purge_after TIMESTAMPTZ,
        ADD COLUMN deletion_settled_at TIMESTAMPTZ,
        ADD COLUMN purged_at TIMESTAMPTZ,
        ADD COLUMN deletion_retry_at TIMESTAMPTZ;
    UPDATE orgs_organizations SET purge_after = deleted_at + INTERVAL '30 days' WHERE deleted_at IS NOT NULL;
    CREATE INDEX orgs_organizations_to_settle ON orgs_organizations (deleted_at, id)
        WHERE deleted_at IS NOT NULL AND deletion_settled_at IS NULL;
    CREATE INDEX orgs_organizations_to_purge ON orgs_organizations (purge_after, id)
        WHERE purge_after IS NOT NULL AND purged_at IS NULL;
    DROP FUNCTION orgs_memberships_of(UUID);
    CREATE FUNCTION orgs_memberships_of(p_user UUID) RETURNS TABLE (
        id UUID, slug TEXT, name TEXT, github_org_id BIGINT, free_claim TEXT, created_at TIMESTAMPTZ,
        time_zone TEXT, member_id UUID, org_id UUID, user_id UUID, role TEXT, joined_at TIMESTAMPTZ
    ) LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT o.id, o.slug, o.name, o.github_org_id, o.free_claim, o.created_at, o.time_zone,
               m.id, m.org_id, m.user_id, m.role, m.joined_at
        FROM orgs_members m JOIN orgs_organizations o ON o.id = m.org_id
        WHERE m.user_id = p_user AND o.deleted_at IS NULL;
    $$;
    CREATE FUNCTION orgs_deletions_to_settle(p_now TIMESTAMPTZ, p_limit INTEGER) RETURNS SETOF UUID
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT id FROM orgs_organizations
        WHERE deleted_at IS NOT NULL AND deletion_settled_at IS NULL
          AND (deletion_retry_at IS NULL OR deletion_retry_at <= p_now)
        ORDER BY deleted_at, id LIMIT p_limit;
    $$;
    CREATE FUNCTION orgs_due_purges(p_now TIMESTAMPTZ, p_limit INTEGER) RETURNS SETOF UUID
        LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT id FROM orgs_organizations
        WHERE purge_after IS NOT NULL AND purged_at IS NULL AND purge_after <= p_now
          AND deletion_settled_at IS NOT NULL
          AND (deletion_retry_at IS NULL OR deletion_retry_at <= p_now)
        ORDER BY purge_after, id LIMIT p_limit;
    $$;
    ${DELETION_FUNCTIONS.joinToString("\n") { "REVOKE EXECUTE ON FUNCTION $it FROM PUBLIC;" }}
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            ${DELETION_FUNCTIONS.joinToString("\n            ") { "GRANT EXECUTE ON FUNCTION $it TO ostrom_app;" }}
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ${DELETION_FUNCTIONS.joinToString("\n") { "ALTER FUNCTION $it OWNER TO ostrom_router;" }}
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

/**
 * Version 4: row-level security forced on the three tables (RLS audit R5), as on every other tenant table. The serving
 * role owns nothing and was already bound by the policies; forcing them puts the tables' owner, the role that migrates,
 * under them too (it still passes the `ostrom_router` policies, being a member of the role it hands the functions to).
 * What crosses organizations already goes through the `ostrom_router` functions of versions 2 and 3.
 */
private const val FORCED_SQL: String =
    """
    ALTER TABLE orgs_organizations FORCE ROW LEVEL SECURITY;
    ALTER TABLE orgs_members FORCE ROW LEVEL SECURITY;
    ALTER TABLE orgs_invitations FORCE ROW LEVEL SECURITY
    """

/**
 * Version 8: how long the launcher waits for the first hook of an agent session (`hooks_deadline_seconds`, 15 until an
 * owner or admin sets it, 5 to 300 whatever writes it). `orgs_claim_holders` and `orgs_find_by_slug` return the table's
 * own rows and carry the column as it is; `orgs_memberships_of` is made again to return it, as version 3 did for the
 * time zone.
 */
private val HOOKS_DEADLINE_SQL: String =
    """
    ALTER TABLE orgs_organizations
        ADD COLUMN hooks_deadline_seconds INTEGER NOT NULL DEFAULT 15
        CONSTRAINT orgs_organizations_hooks_deadline CHECK (hooks_deadline_seconds BETWEEN 5 AND 300);
    DROP FUNCTION orgs_memberships_of(UUID);
    CREATE FUNCTION orgs_memberships_of(p_user UUID) RETURNS TABLE (
        id UUID, slug TEXT, name TEXT, github_org_id BIGINT, free_claim TEXT, created_at TIMESTAMPTZ,
        time_zone TEXT, hooks_deadline_seconds INTEGER, member_id UUID, org_id UUID, user_id UUID, role TEXT,
        joined_at TIMESTAMPTZ
    ) LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
        SELECT o.id, o.slug, o.name, o.github_org_id, o.free_claim, o.created_at, o.time_zone,
               o.hooks_deadline_seconds, m.id, m.org_id, m.user_id, m.role, m.joined_at
        FROM orgs_members m JOIN orgs_organizations o ON o.id = m.org_id
        WHERE m.user_id = p_user AND o.deleted_at IS NULL;
    $$;
    REVOKE EXECUTE ON FUNCTION orgs_memberships_of(UUID) FROM PUBLIC;
    DO $$ BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ostrom_app') THEN
            GRANT EXECUTE ON FUNCTION orgs_memberships_of(UUID) TO ostrom_app;
        END IF;
    END $$;
    GRANT CREATE ON SCHEMA public TO ostrom_router;
    ALTER FUNCTION orgs_memberships_of(UUID) OWNER TO ostrom_router;
    REVOKE CREATE ON SCHEMA public FROM ostrom_router;
    """
        .trimIndent()

/**
 * The schema of the orgs module; its tables start with `orgs_`. Every table has forced row-level security
 * on the organization (`ostrom.org_id`, `run.ostrom.server.kernel.database.inOrg`): the
 * organization's own row, its members and its invitations are only visible to a query confined to
 * it. The cross-organization paths (creating an organization, the free-plan claim, a short name, a
 * user's organizations, an invitation found by its token) go through the `SECURITY DEFINER`
 * functions of version 2, owned by `ostrom_router` (docs/tenancy.md): the serving role is subject to
 * row-level security and could not take them otherwise.
 */
val OrgsMigrations: List<SchemaMigration> =
    listOf(
        SchemaMigration(
            version = 1,
            name = "create_orgs",
            sql =
                """
            CREATE TABLE orgs_organizations (
                id UUID PRIMARY KEY,
                slug TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                github_org_id BIGINT,
                free_claim TEXT,
                created_at TIMESTAMPTZ NOT NULL,
                deleted_at TIMESTAMPTZ
            );
            CREATE INDEX orgs_organizations_free_claim ON orgs_organizations (free_claim) WHERE deleted_at IS NULL;
            CREATE TABLE orgs_members (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL REFERENCES orgs_organizations (id),
                user_id UUID NOT NULL REFERENCES identity_users (id) ON DELETE CASCADE,
                role TEXT NOT NULL CHECK (role IN ('owner', 'admin', 'member', 'approver', 'billing')),
                joined_at TIMESTAMPTZ NOT NULL,
                UNIQUE (org_id, user_id)
            );
            CREATE UNIQUE INDEX orgs_members_one_owner ON orgs_members (org_id) WHERE role = 'owner';
            CREATE INDEX orgs_members_user ON orgs_members (user_id);
            CREATE TABLE orgs_invitations (
                id UUID PRIMARY KEY,
                org_id UUID NOT NULL REFERENCES orgs_organizations (id),
                token_hash TEXT NOT NULL UNIQUE,
                email TEXT,
                role TEXT NOT NULL CHECK (role IN ('admin', 'member', 'approver', 'billing')),
                invited_by UUID NOT NULL,
                created_at TIMESTAMPTZ NOT NULL,
                expires_at TIMESTAMPTZ NOT NULL,
                max_uses INTEGER,
                uses INTEGER NOT NULL DEFAULT 0,
                revoked_at TIMESTAMPTZ
            );
            CREATE INDEX orgs_invitations_org ON orgs_invitations (org_id, created_at);
            ALTER TABLE orgs_organizations ENABLE ROW LEVEL SECURITY;
            CREATE POLICY orgs_organizations_tenant ON orgs_organizations
                USING (id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID))
                WITH CHECK (id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID));
            GRANT SELECT, UPDATE ON orgs_organizations TO ostrom_tenant;
            ALTER TABLE orgs_members ENABLE ROW LEVEL SECURITY;
            CREATE POLICY orgs_members_tenant ON orgs_members
                USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID))
                WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID));
            GRANT SELECT, INSERT, UPDATE, DELETE ON orgs_members TO ostrom_tenant;
            ALTER TABLE orgs_invitations ENABLE ROW LEVEL SECURITY;
            CREATE POLICY orgs_invitations_tenant ON orgs_invitations
                USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID))
                WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID));
            GRANT SELECT, INSERT, UPDATE, DELETE ON orgs_invitations TO ostrom_tenant
        """
                    .trimIndent(),
        ),
        SchemaMigration(
            version = 2,
            name = "orgs_cross_organization_functions",
            sql = CROSS_ORGANIZATION_SQL,
        ),
        SchemaMigration(
            version = 3,
            name = "orgs_time_zone_and_deletion",
            sql = SETTINGS_AND_DELETION_SQL,
        ),
        SchemaMigration(
            version = 4,
            name = "orgs_row_level_security_forced",
            sql = FORCED_SQL.trimIndent(),
        ),
        // RLS audit R9: the router's policies hold only inside its own functions, as identity's
        // do; the owner, a member of `ostrom_router` to hand them over, no longer passes them.
        SchemaMigration(
            version = 5,
            name = "orgs_router_policies_inside_functions_only",
            sql = """
                ALTER POLICY orgs_organizations_router ON orgs_organizations USING (current_user = 'ostrom_router')
                    WITH CHECK (current_user = 'ostrom_router');
                ALTER POLICY orgs_members_router ON orgs_members USING (current_user = 'ostrom_router')
                    WITH CHECK (current_user = 'ostrom_router');
                ALTER POLICY orgs_invitations_router ON orgs_invitations USING (current_user = 'ostrom_router')
            """.trimIndent(),
        ),
        // The short name of a deleted organization stays taken for its 30 days of grace, so that it
        // can be restored, and is given back when it is erased: the erased ones before this version
        // give theirs back now. A purged row keeps a name no one can choose (`~` and its id).
        SchemaMigration(
            version = 6,
            name = "orgs_purged_short_names_released",
            sql = """
                ALTER TABLE orgs_organizations NO FORCE ROW LEVEL SECURITY;
                UPDATE orgs_organizations SET slug = $PURGED_SLUG WHERE purged_at IS NOT NULL;
                ALTER TABLE orgs_organizations FORCE ROW LEVEL SECURITY
            """.trimIndent(),
        ),
        SchemaMigration(version = 7, name = "orgs_member_search", sql = MEMBER_SEARCH_SQL),
        // The organization's wait for its hooks (`hooksDeadlineSeconds`, server#297, audit M3).
        SchemaMigration(
            version = 8,
            name = "orgs_hooks_deadline",
            sql = HOOKS_DEADLINE_SQL,
        ),
    )

/** The short name an erased organization keeps, in SQL: `~` and its id, outside the pattern of the short names. */
internal const val PURGED_SLUG = "'~' || CAST(id AS TEXT)"
