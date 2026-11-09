// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.app.isolation

/** The routes a service key may succeed on, by the one scope it holds (SERVICE_KEY_SCOPES, ServiceKeyCalls.kt). */
internal val SERVICE_KEY_ROUTES: Map<String, Set<String>> = mapOf(
    "rules:read" to setOf("GET /api/v1/rules", "GET /api/v1/rules/{id}"),
    "sessions:write" to setOf("POST /api/v1/checks", "POST /api/v1/sessions/{id}/workflow-receipt"),
)

/**
 * The routes every credential reads by contract, a service key included, with no scope: they answer what the server
 * speaks and serves, the same for every caller, nothing of an organization's (ostrom-core's `capabilities`).
 */
internal val ANY_CREDENTIAL_ROUTES: Set<String> = setOf("GET /api/v1/capabilities")

/** The kinds of resource an agent token may only reach in its own launch. */
internal val LAUNCH_SEEDS: Set<Seed> = setOf(Seed.LAUNCH, Seed.SESSION, Seed.GATE, Seed.APPROVAL, Seed.WAIT)

/** The cases of [registry] that name one of [seeds], in their path or their body. */
internal fun casesNaming(registry: Map<String, Case>, seeds: Set<Seed>): Map<String, Case> =
    registry.filterValues { case ->
        case is Case.Target && (
            case.params.values.any { it is Param.Of && it.seed in seeds } ||
                seeds.any { "{{${it.name}}}" in case.body.orEmpty() }
            )
    }

/**
 * [member], a simple member, sends every write the registry reserves to owners and admins, on its own organization's
 * resources (this matrix's values): each that succeeds is a finding.
 */
internal suspend fun IsolationMatrix.adminWrites(registry: Map<String, Case>, member: Credential): List<String> =
    registry.filterValues { it is Case.Target && it.admin }.mapNotNull { (route, case) ->
        val resolved = resolve(route, case as Case.Target)
        val answer = resolved?.let { call(route, it, member) }
        when {
            answer == null -> "$route: not tried, the organization has none of what it names"
            answer.succeeded -> "$route [${member.name}]: a simple member's write answers ${answer.status}"
            else -> null
        }
    }

/**
 * A service key holding [scope] alone calls every route on its own organization's resources (this matrix's values):
 * it may succeed only where [SERVICE_KEY_ROUTES] lets it, on [ANY_CREDENTIAL_ROUTES], or where [anonymous] succeeds too
 * (a public route).
 */
internal suspend fun IsolationMatrix.serviceKeyReach(
    registry: Map<String, Case>,
    scope: String,
    key: Credential,
    anonymous: Credential,
): List<String> = registry.filterValues { it is Case.Target }.mapNotNull { (route, case) ->
    val resolved = resolve(route, case as Case.Target) ?: return@mapNotNull null
    val answer = call(route, resolved, key)
    val allowed = route in SERVICE_KEY_ROUTES.getValue(scope) || route in ANY_CREDENTIAL_ROUTES
    if (answer.succeeded && !allowed && !call(route, resolved, anonymous).succeeded) {
        "$route [${key.name}]: a key holding only $scope answers ${answer.status}"
    } else {
        null
    }
}
