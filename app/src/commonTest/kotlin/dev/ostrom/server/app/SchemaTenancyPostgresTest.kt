// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tenancy rules of `docs/tenancy.md`, read from PostgreSQL's catalog once every module has migrated: they hold for
 * every table, policy, grant and function of the schema, including a table that no module test names. Each check
 * compares what it finds with the known exceptions it lists: a new hole fails, and so does a fixed one still listed.
 */
class SchemaTenancyPostgresTest {
    @Test
    fun everyTableOfAnOrganizationIsUnderForcedRowLevelSecurity() = schemaTest { server ->
        val found = server.rows(ORG_TABLES, "name", "rls", "forced").flatMap { table ->
            val name = table.getValue("name")
            when {
                table["rls"] != "true" -> listOf("$name: row level security disabled")
                table["forced"] != "true" -> listOf("$name: row level security not forced")
                else -> emptyList()
            }
        }
        assertEquals(UNCONFINED_TABLES, found.toSet(), "Org tables without forced row level security")
    }

    @Test
    fun everyPolicyOfAnOrganizationsTableIsBoundToItsOrganization() = schemaTest { server ->
        val unbound = server.rows(UNBOUND_POLICIES, "tablename", "policyname", "cmd")
            .map { "${it["tablename"]}.${it["policyname"]} (${it["cmd"]})" }
        val missing = server.rows(TABLES_WITHOUT_ORG_POLICY, "name").map { "${it["name"]}: no policy on ostrom.org_id" }
        assertEquals(UNBOUND, (unbound + missing).toSet(), "Policies that open rows beyond the organization")
    }

    @Test
    fun noRoleOfTheServerHoldsAPrivilegeThatBypassesRowLevelSecurity() = schemaTest { server ->
        val dangerous = listOf(server.database.servingRole, TENANT).flatMap { role ->
            server.rows(dangerousPrivileges(role), "name", "privilege")
                .map { "$role: ${it["privilege"]} on ${it["name"]}" }
        }
        val public = server.rows(PUBLIC_TABLES, "name", "privilege")
            .map { "PUBLIC: ${it["privilege"]} on ${it["name"]}" }
        val unconfined = server.rows(tenantWritesWithoutRls(), "name", "privilege")
            .map { "$TENANT: ${it["privilege"]} on ${it["name"]} without RLS" }
        assertEquals(TOO_WIDE_GRANTS, (dangerous + public + unconfined).toSet(), "Grants wider than a request needs")
    }

    @Test
    fun everySecurityDefinerFunctionIsPinnedAndNarrow() = schemaTest { server ->
        val columns = arrayOf("name", "owner", "search_path", "public_execute")
        val found = server.rows(DEFINER_FUNCTIONS, *columns).flatMap { function ->
            val name = function.getValue("name")
            // One proconfig entry, `search_path=pg_catalog, public, pg_temp`: its own commas are not separators.
            val searchPath = function["search_path"]
            listOfNotNull(
                "$name: no search_path".takeIf { searchPath == null },
                "$name: pg_temp not last in its search_path".takeIf {
                    searchPath != null && !searchPath.trimEnd().endsWith("pg_temp")
                },
                "$name: EXECUTE granted to PUBLIC".takeIf { function["public_execute"] == "true" },
                "$name: owned by ${function["owner"]}".takeIf { function["owner"] != ROUTER },
            )
        }
        assertEquals(emptySet(), found.toSet(), "SECURITY DEFINER functions outside the rules")
    }

    @Test
    fun theCatalogReport() = schemaTest { server ->
        // The audit's inventory (docs/tenancy.md): every table, whether it carries an organization, and its policies.
        val tables = server.rows(INVENTORY, "name", "org_id", "rls", "forced", "policies")
        println(tables.joinToString("\n", prefix = "-- schema inventory\n") { it.values.joinToString(" | ") })
        assertEquals(tables.size, tables.map { it["name"] }.toSet().size, "One line per table")
    }

    private fun schemaTest(block: suspend (TestServer) -> Unit) =
        serverTest(serverModules()) { server -> block(server) }

    private suspend fun TestServer.rows(sql: String, vararg columns: String): List<Map<String, String?>> =
        database.superuser.fetchAll(sql).getOrThrow().map { row ->
            columns.associateWith { row.get(it).asStringOrNull() }
        }

    private companion object {
        const val TENANT = "ostrom_tenant"
        const val ROUTER = "ostrom_router"

        /** The tables that hold an organization's rows: an `org_id` column, or the organizations themselves. */
        const val ORG_TABLE_FILTER = """
            n.nspname = 'public' AND c.relkind IN ('r', 'p') AND (c.relname = 'orgs_organizations' OR EXISTS (
                SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'org_id' AND NOT a.attisdropped
            ))
        """

        const val ORG_TABLES = """
            SELECT c.relname AS name, CAST(c.relrowsecurity AS TEXT) AS rls,
                CAST(c.relforcerowsecurity AS TEXT) AS forced
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE $ORG_TABLE_FILTER ORDER BY 1
        """

        /**
         * A policy that a request's role meets (any role but `ostrom_router`, whose functions are listed in
         * `docs/tenancy.md`) and that does not compare the row's organization column with `ostrom.org_id`, or that
         * another setting opens: an INSERT policy is read from its WITH CHECK, the only rule it has.
         */
        const val UNBOUND_POLICIES = """
            SELECT p.tablename, p.policyname, p.cmd
            FROM pg_policies p JOIN pg_class c ON c.relname = p.tablename
            JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = p.schemaname
            CROSS JOIN LATERAL (
                SELECT CASE WHEN p.cmd = 'INSERT' THEN COALESCE(p.with_check, '') ELSE COALESCE(p.qual, '') END AS rule,
                    COALESCE(p.qual, '') || ' ' || COALESCE(p.with_check, '') AS rules
            ) AS r
            WHERE $ORG_TABLE_FILTER AND NOT ('ostrom_router' = ALL (p.roles)) AND NOT (
                r.rule LIKE '%current_setting(''ostrom.org_id''%'
                AND replace(r.rule, 'ostrom.org_id', '')
                    ~ ('\m' || CASE WHEN c.relname = 'orgs_organizations' THEN 'id' ELSE 'org_id' END || '\M')
                AND (p.with_check IS NULL OR p.with_check LIKE '%current_setting(''ostrom.org_id''%')
                AND replace(r.rules, 'current_setting(''ostrom.org_id''', '') NOT LIKE '%current_setting(%'
            )
            ORDER BY 1, 2
        """

        const val TABLES_WITHOUT_ORG_POLICY = """
            SELECT c.relname AS name FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE $ORG_TABLE_FILTER AND c.relrowsecurity AND NOT EXISTS (
                SELECT 1 FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname
                    AND p.qual LIKE '%current_setting(''ostrom.org_id''%'
            )
            ORDER BY 1
        """

        /** TRUNCATE skips row level security; REFERENCES and TRIGGER are never needed by a request. */
        fun dangerousPrivileges(role: String) = """
            SELECT c.relname AS name, k.privilege
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            CROSS JOIN (VALUES ('TRUNCATE'), ('REFERENCES'), ('TRIGGER')) AS k (privilege)
            WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND has_table_privilege('$role', c.oid, k.privilege)
            ORDER BY 1, 2
        """

        const val PUBLIC_TABLES = """
            SELECT c.relname AS name, a.privilege_type AS privilege
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace, aclexplode(c.relacl) a
            WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND a.grantee = 0
            ORDER BY 1, 2
        """

        fun tenantWritesWithoutRls() = """
            SELECT c.relname AS name, k.privilege
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            CROSS JOIN (VALUES ('INSERT'), ('UPDATE'), ('DELETE')) AS k (privilege)
            WHERE $ORG_TABLE_FILTER AND NOT c.relforcerowsecurity
                AND has_table_privilege('$TENANT', c.oid, k.privilege)
            ORDER BY 1, 2
        """

        const val DEFINER_FUNCTIONS = """
            SELECT CAST(CAST(p.oid AS regprocedure) AS TEXT) AS name, pg_get_userbyid(p.proowner) AS owner,
                (SELECT c FROM unnest(p.proconfig) AS c WHERE c LIKE 'search_path=%') AS search_path,
                CAST(p.proacl IS NULL OR EXISTS (
                    SELECT 1 FROM aclexplode(p.proacl) a WHERE a.grantee = 0 AND a.privilege_type = 'EXECUTE'
                ) AS TEXT) AS public_execute
            FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'public' AND p.prosecdef
            ORDER BY 1
        """

        const val INVENTORY = """
            SELECT c.relname AS name,
                CAST(EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'org_id') AS TEXT)
                    AS org_id,
                CAST(c.relrowsecurity AS TEXT) AS rls, CAST(c.relforcerowsecurity AS TEXT) AS forced,
                COALESCE((SELECT string_agg(p.policyname || ' ' || p.cmd || ' ' || array_to_string(p.roles, ','), '; ')
                    FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname), '') AS policies
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
            ORDER BY 1
        """

        // The known exceptions, each a finding of the RLS audit (docs/tenancy.md, "Known exceptions"): remove a line
        // with the fix that closes it.
        val UNCONFINED_TABLES = setOf(
            "artifacts_routes: row level security disabled",
            "automations_routes: row level security disabled",
            "runners_session_routes: row level security disabled",
            "billing_customers: row level security disabled",
            "billing_outbox: row level security disabled",
            "orgs_organizations: row level security not forced",
            "orgs_members: row level security not forced",
            "orgs_invitations: row level security not forced",
        )

        val UNBOUND = setOf(
            "sessions_sessions.sessions_sessions_sweep (SELECT)",
            "usage_events.usage_events_retention_read (SELECT)",
            "usage_events.usage_events_retention (DELETE)",
            "mcp_relay_calls.mcp_relay_calls_retention_read (SELECT)",
            "mcp_relay_calls.mcp_relay_calls_retention (DELETE)",
            "github_installations.github_installations_read (SELECT)",
            "github_installations.github_installations_delete (DELETE)",
            "github_install_states.github_install_states_read (SELECT)",
            "github_install_states.github_install_states_delete (DELETE)",
        )

        val TOO_WIDE_GRANTS = setOf(
            "$TENANT: INSERT on artifacts_routes without RLS",
            "$TENANT: UPDATE on artifacts_routes without RLS",
            "$TENANT: INSERT on automations_routes without RLS",
            "$TENANT: UPDATE on automations_routes without RLS",
            "$TENANT: INSERT on runners_session_routes without RLS",
            "$TENANT: UPDATE on runners_session_routes without RLS",
            "$TENANT: UPDATE on orgs_organizations without RLS",
            "$TENANT: INSERT on orgs_members without RLS",
            "$TENANT: UPDATE on orgs_members without RLS",
            "$TENANT: DELETE on orgs_members without RLS",
            "$TENANT: INSERT on orgs_invitations without RLS",
            "$TENANT: UPDATE on orgs_invitations without RLS",
            "$TENANT: DELETE on orgs_invitations without RLS",
        )
    }
}
