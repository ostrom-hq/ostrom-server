// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.app.isolation

private val USAGE_READS = setOf(
    "GET /api/v1/usage", "GET /api/v1/usage/summary", "GET /api/v1/usage/events", "GET /api/v1/usage/live",
    "GET /api/v1/mcp/usage", "GET /api/v1/spending",
)

private val CLOUD_READS = setOf(
    "GET /api/v1/cloud/sessions", "GET /api/v1/cloud/sessions/{id}", "GET /api/v1/runners",
)

private val CLOUD_WRITES = CLOUD_READS + setOf(
    "POST /api/v1/cloud/sessions", "DELETE /api/v1/cloud/sessions/{id}", "POST /api/v1/cloud/sessions/{id}/pause",
    "POST /api/v1/cloud/sessions/{id}/resume", "POST /api/v1/cloud/sessions/{id}/terminal/ticket",
)

private val ARTIFACT_READS = setOf(
    "GET /api/v1/artifacts", "GET /api/v1/artifacts/{id}", "GET /api/v1/artifacts/{id}/versions",
    "GET /api/v1/artifacts/{id}/versions/{number}/file", "GET /api/v1/artifacts/{id}/members",
    "POST /api/v1/artifacts/{id}/tickets", "POST /api/v1/artifacts/mcp",
)

/**
 * The routes a service key may succeed on, by the one scope it holds (SERVICE_KEY_SCOPES, ServiceKeyCalls.kt): the CI's
 * two, and the member scopes, which act as the owner who made the key within them. Never an access change, a delete of
 * an artifact, or a write on the organization.
 */
internal val SERVICE_KEY_ROUTES: Map<String, Set<String>> = mapOf(
    "rules:read" to setOf("GET /api/v1/rules", "GET /api/v1/rules/{id}"),
    "sessions:write" to setOf("POST /api/v1/checks", "POST /api/v1/sessions/{id}/workflow-receipt"),
    "inference:use" to setOf(
        "GET /api/v1/inference/v1/models", "POST /api/v1/inference/v1/messages", "POST /api/v1/inference/v1/responses",
        "POST /api/v1/inference/v1/chat/completions",
    ),
    "mcp:use" to setOf("POST /api/v1/mcp/{server}"),
    "usage:read" to USAGE_READS,
    "cloud:read" to CLOUD_READS,
    "cloud:write" to CLOUD_WRITES,
    "cloud:admin" to CLOUD_WRITES,
    "artifacts:read" to ARTIFACT_READS,
    "artifacts:write" to ARTIFACT_READS + setOf(
        "POST /api/v1/artifacts", "POST /api/v1/artifacts/{id}/versions", "PUT /api/v1/artifacts/{id}/members/{login}",
        "DELETE /api/v1/artifacts/{id}/members/{login}",
    ),
    "team:read" to setOf(
        "GET /api/v1/usage", "GET /api/v1/usage/summary", "GET /api/v1/usage/events", "GET /api/v1/usage/live",
        "GET /api/v1/mcp/usage", "GET /api/v1/spending", "GET /api/v1/budgets", "GET /api/v1/accounts",
        "GET /api/v1/capacity", "GET /api/v1/orgs/{org}/members",
    ),
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
