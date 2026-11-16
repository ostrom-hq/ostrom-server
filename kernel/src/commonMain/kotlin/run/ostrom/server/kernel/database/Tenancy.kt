// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

/**
 * The PostgreSQL role every organization-scoped query runs as. It is not a superuser and owns no table, so the
 * row-level security policies of the tenant tables always apply to it, even when the server connects as the
 * tables' owner or as a superuser (which bypass them). The identity module's first migration creates it and grants
 * it to the server's user.
 */
const val TENANT_ROLE = "ostrom_tenant"

/**
 * The transaction-local setting the row-level security policies read: the organization of the query
 * ([JdbcDatabase.inOrg]). A tenant table declares, in its module's migration:
 * ```sql
 * ALTER TABLE orgs_members ENABLE ROW LEVEL SECURITY;
 * CREATE POLICY orgs_members_tenant ON orgs_members
 *     USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID))
 *     WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID));
 * GRANT SELECT, INSERT, UPDATE, DELETE ON orgs_members TO ostrom_tenant;
 * ```
 */
const val TENANT_SETTING = "ostrom.org_id"

/**
 * The SQL of a migration that creates [TENANT_ROLE] (tolerating one created by another module, database or test) and
 * grants it to the migrating user, before the migration's own `GRANT … TO ostrom_tenant`: roles are global to the
 * PostgreSQL cluster. A module whose tables are queried in [JdbcDatabase.inOrg], which switches to the role, grants
 * its tables with it.
 */
const val TENANT_ROLE_SQL: String = """
DO $$
BEGIN
    CREATE ROLE $TENANT_ROLE NOLOGIN;
EXCEPTION WHEN duplicate_object OR unique_violation THEN
    NULL;
END
$$;
DO $$
BEGIN
    GRANT $TENANT_ROLE TO CURRENT_USER;
EXCEPTION WHEN unique_violation THEN
    NULL;
END
$$;
"""
