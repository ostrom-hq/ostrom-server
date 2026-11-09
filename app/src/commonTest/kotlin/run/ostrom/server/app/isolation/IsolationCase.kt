// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.app.isolation

import kotlin.random.Random

/** A resource an organization's world holds, named by a route's path or body: B's own value, or a lookalike. */
internal enum class Seed {
    ORG_ID, ORG_SLUG, MEMBER_LOGIN, MEMBER_ID, INVITATION, GROUP, ACCOUNT, ARTIFACT, LINK_SLUG, AUTOMATION, WAIT,
    SECRET, MCP_SERVER, MCP_POLICY, RULE, WORKFLOW, RUN, PACKAGE, LAUNCH, SESSION, GATE, APPROVAL, RECEIPT,
    CONFIRMATION, CLOUD_SESSION, RUNNER, SERVICE_KEY, TOKEN, PUSH_DEVICE, AUTH_SESSION, DEVICE,
}

/** What a path parameter or a body placeholder is replaced with. */
internal sealed interface Param {
    /** B's value of [seed] in the attack, a random value of the same shape in its twin. */
    data class Of(val seed: Seed) : Param

    /** The same value in both requests: what the parameter names is not B's (a file path, a provider). */
    data class Fixed(val value: String) : Param

    /** A fresh random value in both requests: a secret the attacker cannot know (a ticket, a link token). */
    data object Fresh : Param
}

/** How the matrix treats one mounted route. */
internal sealed interface Case {
    /**
     * Attacked by every credential of A: once with B's values (path, [body], `Ostrom-Org` header), once with random
     * values of the same shape. Both answers must be the same, and no answer may carry anything of B.
     *
     * @property params The route's path parameters by name.
     * @property body The JSON body, its `{{SEED}}` placeholders replaced like the path; null sends none.
     * @property admin Whether the write is reserved to an owner or an admin of the organization.
     * @property host The `Host` the route answers on, when not the API's (the artifact pages' content origin).
     * @property query The query string the route needs, without its `?`.
     * @property stream Whether a success is a stream that does not end (server-sent events): its body is not read.
     * @property rpc Whether the route speaks JSON-RPC (MCP): a refusal is an `error` or an `isError` result in a 200.
     * @property exclusive Whether the route runs one job at a time for the organization: the second call answers a
     *   conflict whatever it names, so B's value and its lookalike are not compared (leaks still are).
     */
    data class Target(
        val params: Map<String, Param> = emptyMap(),
        val body: String? = null,
        val admin: Boolean = false,
        val host: String? = null,
        val query: String? = null,
        val stream: Boolean = false,
        val rpc: Boolean = false,
        val exclusive: Boolean = false,
    ) : Case

    /**
     * A route that names nothing of an organization and acts on its caller only (`/auth/me`, sign out): its reads are
     * searched for B's data, its writes are not sent.
     */
    data class Own(val reason: String) : Case

    /** Not attacked, for [reason]: a public flow, a capability in a secret (a signature, a code, a runner token). */
    data class Exempt(val reason: String) : Case
}

/** Shorthands for the registry. */
internal fun of(seed: Seed): Param = Param.Of(seed)

internal fun target(
    vararg params: Pair<String, Seed>,
    body: String? = null,
    admin: Boolean = false,
    rpc: Boolean = false,
): Case = Case.Target(params.associate { (name, seed) -> name to of(seed) }, body, admin, rpc = rpc)

/**
 * A value of the same shape as [value], for the twin request: the prefix up to the last `_` or `:` is kept (`rule_`,
 * `sha256:`), every digit and letter after it is redrawn in its class (a hex digit stays hex) and every other character
 * kept, so the server parses both the same way and only the existence of B's resource could tell them apart.
 */
internal fun lookalike(value: String, random: Random = Random.Default): String {
    val cut = value.indexOfLast { it == '_' || it == ':' } + 1
    return value.take(cut) + value.drop(cut).map { char ->
        when (char) {
            in '0'..'9' -> '0' + random.nextInt(10)
            in 'a'..'f' -> 'a' + random.nextInt(6)
            in 'g'..'z' -> 'g' + random.nextInt(20)
            in 'A'..'F' -> 'A' + random.nextInt(6)
            in 'G'..'Z' -> 'G' + random.nextInt(20)
            else -> char
        }
    }.joinToString("")
}
