// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.database

/**
 * The role that owns the cross-organization functions (CONVENTIONS 16.1, SECURITY-AUDIT S2-51): `NOLOGIN`, owner of
 * no table, granted only the rows of the routing tables it reads through a policy of its own. The serving role is
 * never a member of it; it calls the functions, which run as this role (`SECURITY DEFINER`) and touch only the rows
 * of their operation.
 */
const val ROUTER_ROLE: String = "ostrom_router"

/** The transaction-local setting the tenant policies read: the organization of the query. */
private const val ORG_SETTING = "CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID)"

/**
 * The SQL that creates [ROUTER_ROLE] (tolerating one created by another module, database or test; roles are global to
 * the cluster) and makes the migrating user a member, so that it can hand its functions over with
 * [routerFunctionsSql].
 */
val ROUTER_ROLE_SQL: String = """
    DO $$
    BEGIN
        CREATE ROLE $ROUTER_ROLE NOLOGIN NOSUPERUSER NOBYPASSRLS;
    EXCEPTION WHEN duplicate_object OR unique_violation THEN
        NULL;
    END
    $$;
    DO $$
    BEGIN
        GRANT $ROUTER_ROLE TO CURRENT_USER;
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END
    $$;
    GRANT USAGE ON SCHEMA public TO $ROUTER_ROLE;
""".trimIndent()

/**
 * The SQL that confines a routing table (an id, its organization, and what finds it from outside the organization):
 * row-level security enabled and forced, the fail-closed tenant policy of every tenant table, and a policy that lets
 * [ROUTER_ROLE] alone reach every row, for the functions of [routerFunctionsSql].
 *
 * @param table The table, with an `org_id` column.
 * @param routerPrivileges What [ROUTER_ROLE] may do on it, such as `SELECT` or `SELECT, UPDATE`.
 *
 * @return The statements.
 */
fun routedTableSql(table: String, routerPrivileges: String): String = """
    ALTER TABLE $table ENABLE ROW LEVEL SECURITY;
    ALTER TABLE $table FORCE ROW LEVEL SECURITY;
    CREATE POLICY ${table}_tenant ON $table USING (org_id = $ORG_SETTING) WITH CHECK (org_id = $ORG_SETTING);
    CREATE POLICY ${table}_router ON $table TO $ROUTER_ROLE USING (true) WITH CHECK (true);
    GRANT $routerPrivileges ON $table TO $ROUTER_ROLE;
""".trimIndent()

/**
 * The SQL that hands functions over to [ROUTER_ROLE]: an owner needs `CREATE` on the schema, granted for the handover
 * only. `EXECUTE` is revoked from `PUBLIC` and granted to the serving role `ostrom_app` when the deployment created it
 * (audit S2-60, as the orgs module does). Each function must be `SECURITY DEFINER` with
 * `SET search_path = pg_catalog, public, pg_temp` (`pg_temp` last, so a temporary object never stands in for theirs),
 * and take exactly what its operation needs.
 *
 * @param signatures The functions, with their argument types: `runners_route_by_token(TEXT)`.
 *
 * @return The statements.
 */
fun routerFunctionsSql(vararg signatures: String): String = (
    signatures.map { "REVOKE EXECUTE ON FUNCTION $it FROM PUBLIC;" } +
        "DO \$\$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$SERVING_ROLE') THEN" +
        signatures.map { "GRANT EXECUTE ON FUNCTION $it TO $SERVING_ROLE;" } +
        "END IF; END \$\$;" +
        "GRANT CREATE ON SCHEMA public TO $ROUTER_ROLE;" +
        signatures.map { "ALTER FUNCTION $it OWNER TO $ROUTER_ROLE;" } +
        "REVOKE CREATE ON SCHEMA public FROM $ROUTER_ROLE;"
    ).joinToString("\n")

/** The deployment's serving role, the only one that may run the router's functions (S2-60). */
private const val SERVING_ROLE = "ostrom_app"
