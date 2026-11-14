// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        assertEquals(emptySet(), found.toSet(), "Org tables without forced row level security")
    }

    @Test
    fun everyTableEitherHoldsAnOrganizationOrIsListedWithItsReason() = schemaTest { server ->
        // A table without `org_id` escapes the checks above: it must be named in INSTANCE_TABLES (docs/tenancy.md,
        // "Tables without an organization"), with the role that reaches it and why no organization owns its rows.
        val found = server.rows(TABLES_WITHOUT_AN_ORGANIZATION, "name").mapNotNull { it["name"] }
            .filterNot { it.startsWith(FLYWAY_PREFIX) }
        assertEquals(INSTANCE_TABLES.keys, found.toSet(), "Tables without org_id, each listed with its reason")
    }

    @Test
    fun everyPolicyOfAnOrganizationsTableIsBoundToItsOrganization() = schemaTest { server ->
        val unbound = server.rows(UNBOUND_POLICIES, "tablename", "policyname", "cmd")
            .map { "${it["tablename"]}.${it["policyname"]} (${it["cmd"]})" }
        val missing = server.rows(TABLES_WITHOUT_ORG_POLICY, "name").map { "${it["name"]}: no policy on ostrom.org_id" }
        assertEquals(UNBOUND, (unbound + missing).toSet(), "Policies that open rows beyond the organization")
    }

    @Test
    fun everyRouterPolicyHoldsOnlyInsideTheRoutersFunctions() = schemaTest { server ->
        // The owner is a member of ostrom_router (it hands the functions over): a policy `TO ostrom_router USING
        // (true)` would open every row to it. `current_user = 'ostrom_router'` is true only inside those functions.
        val policies = server.rows(ROUTER_POLICIES, "tablename", "policyname", "cmd", "qual", "with_check")
        val found = policies.mapNotNull { policy ->
            val qual = policy["qual"].orEmpty()
            val check = policy["with_check"].orEmpty()
            val rule = if (policy["cmd"] == "INSERT") check else qual
            val checkHolds = policy["cmd"] == "INSERT" || check in setOf("", "false") || insideTheRouter(check)
            "${policy["tablename"]}.${policy["policyname"]}: USING ($qual) WITH CHECK ($check)"
                .takeUnless { insideTheRouter(rule) && checkHolds }
        }
        assertTrue(policies.isNotEmpty(), "The functions' tables have their ostrom_router policies")
        assertEquals(emptySet(), found.toSet(), "Router policies that hold outside the router's functions")
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
        assertEquals(emptySet(), (dangerous + public + unconfined).toSet(), "Grants wider than a request needs")
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
                "$name: owned by ${function["owner"]}".takeIf { function["owner"] !in DEFINER_OWNERS },
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

    /** `current_user = 'ostrom_router'` as the catalog prints it, `(CURRENT_USER = 'ostrom_router'::name)`. */
    private fun insideTheRouter(rule: String): Boolean =
        rule.lowercase().filterNot { it.isWhitespace() || it == '(' || it == ')' }
            .removeSuffix("::name") == "current_user='$ROUTER'"

    private suspend fun TestServer.rows(sql: String, vararg columns: String): List<Map<String, String?>> =
        database.superuser.fetchAll(sql).getOrThrow().map { row ->
            columns.associateWith { row.get(it).asStringOrNull() }
        }

    private companion object {
        const val TENANT = "ostrom_tenant"
        const val ROUTER = "ostrom_router"

        /**
         * The owners of the `SECURITY DEFINER` functions: the router's cross-organization paths, and the member search
         * (orgs migration 7), whose role reads `identity_users` and one organization's members, the session's.
         */
        val DEFINER_OWNERS = setOf(ROUTER, "ostrom_member_search")

        /** The tables that hold an organization's rows: an `org_id` column, or the organizations themselves. */
        const val ORG_TABLE_FILTER = """
            n.nspname = 'public' AND c.relkind IN ('r', 'p') AND (c.relname = 'orgs_organizations' OR EXISTS (
                SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'org_id' AND NOT a.attisdropped
            ))
        """

        /** Every table of the schema that is not an organization's: each one must be in [INSTANCE_TABLES]. */
        const val TABLES_WITHOUT_AN_ORGANIZATION = """
            SELECT c.relname AS name FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND NOT ($ORG_TABLE_FILTER)
            ORDER BY 1
        """

        /** Flyway's histories, one per module (`flyway_<module>`), written by the owner role when it migrates. */
        const val FLYWAY_PREFIX = "flyway_"

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

        /** Every policy given to `ostrom_router`, on any table: its rule must name the router as `current_user`. */
        const val ROUTER_POLICIES = """
            SELECT p.tablename, p.policyname, p.cmd, p.qual, p.with_check FROM pg_policies p
            WHERE p.schemaname = 'public' AND 'ostrom_router' = ANY (p.roles)
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

        /**
         * The tables whose rows belong to no organization, as docs/tenancy.md lists them: the role that reaches each
         * one and why. Adding a table without `org_id` means adding it here and there, or giving it an organization.
         */
        val INSTANCE_TABLES = mapOf(
            "admin_instance_admins" to "serving role: the instance's administrators, who set what serves everyone",
            "admin_settings" to "serving role: the instance's settings sections, their secrets sealed",
            "cloud_clusters" to "serving role: the instance's Kubernetes clusters, their credentials sealed",
            "cloud_runtime" to "serving role: how the instance runs cloud sessions, one row",
            "billing_stripe_events" to "serving role, Stripe webhook: the events already handled, by Stripe's id",
            "github_apps" to "serving role: the instance's one GitHub App, its secrets sealed",
            "github_installation_origins" to "serving role, GitHub webhook: who installed an installation, by its id",
            "github_manifest_states" to "serving role: the state of an instance admin's GitHub App creation, by hash",
            "github_webhook_deliveries" to "serving role, GitHub webhook: the deliveries already received, by id",
            "identity_device_authorizations" to "serving role, device sign-in: a person's pending CLI sign-in",
            "identity_identities" to "serving role, sign-in: a person's external accounts",
            "identity_magic_links" to "serving role, sign-in: e-mail links, by hash",
            "identity_mobile_codes" to "serving role, the app's sign-in: one-time codes, by hash",
            "identity_notification_preferences" to "serving role: a person's preferences, across organizations",
            "identity_oauth_attempts" to "serving role, sign-in: an OAuth round trip's state, by hash",
            "identity_rate_limits" to "serving role, sign-in: the rate limits' windows, by hash, before any org",
            "identity_setup_links" to "serving role, setup: the one-time link of `ostrom-server bootstrap`, by hash",
            "identity_sign_in_links" to "serving role: a signed-in person's one-time link to a browser, by hash",
            "identity_user_emails" to "serving role, sign-in: a person's e-mail addresses",
            "identity_users" to "serving role: the people, members of any number of organizations",
            "identity_web_sessions" to "serving role: a person's browser sessions, by hash, before an organization",
            "instance_info" to "serving role: the instance's one identity row",
            "instance_scheduled_work" to "serving role: the leases of the periodic jobs, one row per job",
            "instance_signing_keys" to "serving role: the public keys of the instance's receipt signatures",
        )

        // The known exceptions, each a finding of the RLS audit (docs/tenancy.md, "Known exceptions"): remove a line
        // with the fix that closes it.
        val UNBOUND = setOf(
            "github_installations.github_installations_read (SELECT)",
            "github_installations.github_installations_delete (DELETE)",
            "github_install_states.github_install_states_read (SELECT)",
            "github_install_states.github_install_states_delete (DELETE)",
        )
    }
}
