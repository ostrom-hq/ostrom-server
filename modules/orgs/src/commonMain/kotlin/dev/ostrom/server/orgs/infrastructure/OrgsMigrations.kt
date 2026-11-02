// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

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
 * The schema of the orgs module; its tables start with `orgs_`. Every table has row-level security
 * on the organization (`ostrom.org_id`, `dev.ostrom.server.kernel.database.inOrg`): the
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
    )
