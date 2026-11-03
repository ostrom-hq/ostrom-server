// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app.isolation

import dev.ostrom.server.kernel.auth.ORG_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

/** A way to call the server as someone: a browser session, a token, nothing at all. */
internal class Credential(val name: String, val apply: HttpRequestBuilder.() -> Unit)

/** What the attacker can see of an answer. */
internal data class Answer(val status: Int, val type: String?, val message: String?, val body: String) {
    val succeeded: Boolean get() = status in 200..299

    /** Whether an attacker could tell this answer from [other], once the values each request sent are masked. */
    fun differsFrom(other: Answer, sent: Collection<String>, otherSent: Collection<String>): Boolean {
        if (succeeded && other.succeeded) return false
        return status != other.status || type != other.type || mask(message, sent) != mask(other.message, otherSent)
    }

    private fun mask(text: String?, values: Collection<String>): String? =
        values.sortedByDescending { it.length }.fold(text) { masked, value -> masked?.replace(value, "<value>") }
}

/** One request of the matrix, resolved: its path, its body, its `Ostrom-Org` header and the values it names. */
internal class Resolved(
    val path: String,
    val body: String?,
    val org: String?,
    val sent: Set<String>,
    val host: String? = null,
)

/**
 * The isolation matrix: every route of [registry] is called by every credential of organization A with B's values,
 * then with lookalikes of them, and the two answers are compared. Each difference, and each of B's [canaries] found
 * in an answer, is a finding: a way for A to learn that B's resource exists, or to read it.
 *
 * @property values B's seeded values.
 * @property canaries What only B's data contains: its ids, names, logins and distinctive figures.
 */
internal class IsolationMatrix(
    private val client: HttpClient,
    private val registry: Map<String, Case>,
    private val values: Map<Seed, String>,
    private val canaries: Set<String>,
) {
    /** What the matrix found, one line each, readable in the CI log. */
    val findings = mutableListOf<String>()

    /** How many requests the matrix sent. */
    var requests = 0
        private set

    /** Runs every case against every credential, reads before writes and creating writes last (see [order]). */
    suspend fun run(credentials: List<Credential>) {
        for ((route, case) in registry.entries.sortedWith(compareBy({ order(it.key) }, { it.key }))) {
            credentials.forEach { play(route, case, it) }
        }
    }

    /**
     * Calls every read [registry] covers as [credential] with B's values, for the integrity snapshot of B: the answers
     * by route. A route whose value B lacks is left out (its seed is reported missing by [attack]).
     */
    suspend fun reads(credential: Credential): Map<String, Answer> =
        registry.entries.filter { (route, case) -> route.startsWith("GET ") && case is Case.Target }
            .mapNotNull { (route, case) ->
                resolve(route, case as Case.Target)?.let { route to call(route, it, credential) }
            }.toMap()

    /**
     * Runs [attacks] between snapshots of what [owner] reads: twice before (what differs between those two moves by
     * itself, a last-seen time, and is compared by its status only), once after.
     *
     * @return One line per read whose answer changed.
     */
    suspend fun watch(owner: Credential, attacks: suspend () -> Unit): List<String> {
        val before = reads(owner)
        val again = reads(owner)
        attacks()
        val after = reads(owner)
        return before.mapNotNull { (route, answer) ->
            val now = after[route]
            val moved = now == null || if (again[route] == answer) now != answer else now.status != answer.status
            if (moved) "$route: the owner's answer changed (${answer.status}, then ${now?.status})" else null
        }
    }

    private suspend fun play(route: String, case: Case, credential: Credential) = when (case) {
        is Case.Target -> attack(route, case, credential)
        is Case.Own -> if (route.startsWith("GET ")) search(route, credential) else Unit
        is Case.Exempt -> Unit
    }

    private suspend fun attack(route: String, case: Case.Target, credential: Credential) {
        val namesB = case.params.values.any { it is Param.Of } || PLACEHOLDER.containsMatchIn(case.body.orEmpty())
        val read = route.startsWith("GET ")
        if (namesB) compare(route, case, credential, header = false)
        if (read && !namesB) search(route, credential)
        compare(route, case, credential, header = true)
    }

    private suspend fun compare(route: String, case: Case.Target, credential: Credential, header: Boolean) {
        val own = resolve(route, case, twin = false, header) ?: return missing(route, case)
        val twin = resolve(route, case, twin = true, header) ?: return missing(route, case)
        val answer = call(route, own, credential)
        val other = call(route, twin, credential)
        val where = "$route [${credential.name}${if (header) ", Ostrom-Org: B" else ""}]"
        if (answer.differsFrom(other, own.sent, twin.sent)) {
            findings += "$where: B's value answers ${answer.describe()}, a lookalike ${other.describe()}"
        }
        leak(where, answer, own.sent)
        leak(where, other, twin.sent)
    }

    private suspend fun search(route: String, credential: Credential) {
        val path = route.substringAfter(' ')
        if (PARAMETER.containsMatchIn(path)) return
        leak("$route [${credential.name}]", call(route, Resolved(path, null, null, emptySet()), credential), emptySet())
    }

    // The values the request sent are cut out first: an answer may repeat them ("no rule rule_x"), and a slug or a
    // login of B contains B's marker.
    private fun leak(where: String, answer: Answer, sent: Set<String>) {
        val body = sent.sortedByDescending { it.length }.fold(answer.body) { text, value -> text.replace(value, "") }
        val found = canaries.filter { body.contains(it) }
        if (found.isNotEmpty()) findings += "$where: the answer (${answer.status}) carries B's ${found.joinToString()}"
    }

    private fun missing(route: String, case: Case.Target) {
        val seeds = case.params.values.filterIsInstance<Param.Of>().map { it.seed } +
            PLACEHOLDER.findAll(case.body.orEmpty()).map { Seed.valueOf(it.groupValues[1]) }
        val absent = (seeds + Seed.ORG_ID).filter { it !in values }.distinct()
        val unknown = PARAMETER.findAll(route).map { it.groupValues[1] }.filter { it !in case.params }.toList()
        findings += "$route: not attacked, ${if (unknown.isEmpty()) "B has no $absent" else "no case for $unknown"}"
    }

    /**
     * The request [case] makes on [route]: with B's values, or with lookalikes of them when [twin], and B's
     * organization (or its lookalike) in the `Ostrom-Org` header when [header]. Null when B lacks a value it names.
     */
    internal fun resolve(route: String, case: Case.Target, twin: Boolean = false, header: Boolean = false):
        Resolved? {
        val filler = Filler(values, twin)
        val path = filler.fill(route.substringAfter(' '), PARAMETER) { case.params[it] } +
            case.query?.let { "?$it" }.orEmpty()
        val body = case.body?.let { text -> filler.fill(text, PLACEHOLDER) { Param.Of(Seed.valueOf(it)) } }
        val org = if (header) filler.value(Param.Of(Seed.ORG_ID)) else null
        val complete = filler.complete && (org != null || !header)
        return if (complete) Resolved(path, body, org, filler.sent, case.host) else null
    }

    /** Sends [resolved] as [credential]. */
    internal suspend fun call(route: String, resolved: Resolved, credential: Credential): Answer {
        requests++
        val response = client.request(resolved.path) {
            method = HttpMethod.parse(route.substringBefore(' '))
            credential.apply(this)
            resolved.org?.let { header(ORG_HEADER, it) }
            resolved.host?.let { header(HttpHeaders.Host, it) }
            resolved.body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }
        val text = response.bodyAsText()
        val error = errorOf(text)
        val type = error?.get("type")?.jsonPrimitive?.content
        return Answer(response.status.value, type, error?.get("message")?.jsonPrimitive?.content, text)
    }

    // The `error` object of an ApiError answer; null for any other body (a list, an HTML page, nothing).
    private fun errorOf(text: String): JsonObject? = try {
        Json.parseToJsonElement(text).jsonObject["error"] as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun Answer.describe(): String = "$status${type?.let { " $it" }.orEmpty()}"

    // Fills one request's path or body: a value named twice gets the same lookalike, a fresh one is drawn each time.
    private class Filler(private val values: Map<Seed, String>, private val twin: Boolean) {
        private val drawn = mutableMapOf<String, String>()
        val sent = mutableSetOf<String>()
        var complete = true
            private set

        fun value(param: Param?): String? = when (param) {
            null -> null
            is Param.Fixed -> param.value
            is Param.Fresh -> Uuid.random().toString()
            is Param.Of -> values[param.seed]?.let { if (twin) drawn.getOrPut(it) { lookalike(it) } else it }
                ?.also { sent += it }
        }

        fun fill(text: String, pattern: Regex, param: (String) -> Param?): String = pattern.replace(text) { match ->
            value(param(match.groupValues[1])) ?: "".also { complete = false }
        }
    }

    private companion object {
        val PARAMETER = Regex("""\{([A-Za-z0-9_]+)(?:\.\.\.)?\??}""")
        val PLACEHOLDER = Regex("""\{\{([A-Z_]+)}}""")

        // Reads first, then deletions, updates and creations: a creation with B's name (an MCP server, a secret) makes
        // A's own resource of that name, which a later read or deletion would find while the lookalike finds nothing.
        val ORDER = listOf("GET", "DELETE", "PATCH", "POST", "PUT")

        fun order(route: String): Int = ORDER.indexOf(route.substringBefore(' '))
    }
}
