// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.infrastructure

import dev.ostrom.server.kernel.database.SchemaMigration

/**
 * The schema of the orgs module; its tables start with `orgs_`. Every table has row-level security
 * on the organization (`ostrom.org_id`, `dev.ostrom.server.kernel.database.inOrg`): the
 * organization's own row, its members and its invitations are only visible to a query confined to
 * it. The server's own role keeps the cross-organization paths (a user's organizations, an
 * invitation found by its token, the free-plan claim).
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
        )
    )
