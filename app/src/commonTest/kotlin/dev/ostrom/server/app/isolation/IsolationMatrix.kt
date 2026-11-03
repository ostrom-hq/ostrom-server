// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app.isolation

import dev.ostrom.server.kernel.auth.ORG_HEADER
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** A way to call the server as someone: a browser session, a token, nothing at all. */
internal class Credential(val name: String, val apply: HttpRequestBuilder.() -> Unit)

/**
 * What the attacker can see of an answer.
 *
 * @property type The `error.type` of an ApiError, or the JSON-RPC refusal of an MCP answer.
 * @property succeeded Whether the call did what it asked: a 2xx, without a JSON-RPC refusal.
 */
internal data class Answer(
    val status: Int,
    val type: String?,
    val message: String?,
    val body: String,
    val succeeded: Boolean = status in 200..299,
) {
    /** Whether an attacker could tell this answer from [other], once the values each request sent are masked. */
    fun differsFrom(other: Answer, sent: Collection<String>, otherSent: Collection<String>): Boolean {
        if (succeeded && other.succeeded) return false
        return status != other.status || type != other.type || mask(message, sent) != mask(other.message, otherSent)
    }

    // The values sent, then the instants: two calls a few milliseconds apart name different times ("ended at …").
    private fun mask(text: String?, values: Collection<String>): String? =
        values.sortedByDescending { it.length }.fold(text) { masked, value -> masked?.replace(value, "<value>") }
            ?.replace(INSTANT, "<time>")

    private companion object {
        val INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z""")
    }
}

/** One request of the matrix, resolved: its path, its body, its `Ostrom-Org` header and the values it names. */
internal class Resolved(
    val path: String,
    val body: String?,
    val org: String?,
    val sent: Set<String>,
    val case: Case.Target = Case.Target(),
)

/**
 * The isolation matrix: every route of [registry] is called by every credential of organization A with B's values,
 * then with lookalikes of them, and the two answers are compared. Each difference, and each of B's [canaries] found
 * in an answer, is a finding: a way for A to learn that B's resource exists, or to read it.
 *
 * @property values B's seeded values.
 * @property canaries What only B's data contains: its ids, names, logins and distinctive figures.
 * @property strict Whether B's value must answer as a lookalike does; when not (another launch of the caller's own
 *   organization, whose existence is not a secret to it), only a success with B's value is a finding.
 */
internal class IsolationMatrix(
    private val client: HttpClient,
    private val registry: Map<String, Case>,
    private val values: Map<Seed, String>,
    private val canaries: Set<String>,
    private val strict: Boolean = true,
) {
    /** What the matrix found, one line each, readable in the CI log. */
    val findings = mutableListOf<String>()

    // Each of B's values has one lookalike for the whole matrix: a creation with a lookalike (a session named by the
    // caller) makes the twin world that a later twin request finds, as a creation with B's value makes A's own.
    private val twins = mutableMapOf<String, String>()

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
        when {
            answer.status == NO_ANSWER || other.status == NO_ANSWER -> findings += "$where: no answer in $CALL_TIMEOUT"
            strict && !case.exclusive && answer.differsFrom(other, own.sent, twin.sent) ->
                findings += "$where: B's value answers ${answer.describe()}, a lookalike ${other.describe()}"
            !strict && answer.succeeded -> findings += "$where: B's value answers ${answer.describe()}"
        }
        leak(where, answer, own.sent)
        leak(where, other, twin.sent)
    }

    private suspend fun search(route: String, credential: Credential) {
        val path = route.substringAfter(' ')
        if (PARAMETER.containsMatchIn(path)) return
        val case = registry[route] as? Case.Target ?: Case.Target()
        val answer = call(route, Resolved(path, null, null, emptySet(), case), credential)
        leak("$route [${credential.name}]", answer, emptySet())
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
            PLACEHOLDER.findAll(case.body.orEmpty() + case.query.orEmpty()).map { Seed.valueOf(it.groupValues[1]) }
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
        val filler = Filler(values, twins.takeIf { twin })
        val placeholder = { name: String -> Param.Of(Seed.valueOf(name)) }
        val query = case.query?.let { "?" + filler.fill(it, PLACEHOLDER, placeholder) }.orEmpty()
        val path = filler.fill(route.substringAfter(' '), PARAMETER) { case.params[it] } + query
        val body = case.body?.let { filler.fill(it, PLACEHOLDER, placeholder) }
        val org = if (header) filler.value(Param.Of(Seed.ORG_ID)) else null
        val complete = filler.complete && (org != null || !header)
        return if (complete) Resolved(path, body, org, filler.sent, case) else null
    }

    /** Sends [resolved] as [credential]; a call with no answer in [CALL_TIMEOUT] is a [NO_ANSWER]. */
    internal suspend fun call(route: String, resolved: Resolved, credential: Credential): Answer {
        requests++
        val request = client.prepareRequest(resolved.path) {
            method = HttpMethod.parse(route.substringBefore(' '))
            credential.apply(this)
            resolved.org?.let { header(ORG_HEADER, it) }
            resolved.case.host?.let { header(HttpHeaders.Host, it) }
            // A write without a body of its case still sends JSON: the server reads the body before the resource.
            (resolved.body ?: "{}".takeIf { !route.startsWith("GET ") })?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }
        return withTimeoutOrNull(CALL_TIMEOUT) { request.execute { answerOf(it, resolved.case) } }
            ?: Answer(NO_ANSWER, null, null, "")
    }

    private suspend fun answerOf(response: HttpResponse, case: Case.Target): Answer {
        val status = response.status.value
        if (case.stream && status in 200..299) return Answer(status, null, null, "")
        val text = response.bodyAsText()
        val json = objectOf(text)
        val error = json?.get("error") as? JsonObject
        val result = json?.get("result") as? JsonObject
        val refused = result?.get("isError")?.jsonPrimitive?.content == "true"
        return when {
            !case.rpc -> Answer(status, error?.text("type"), error?.text("message"), text)
            error != null -> Answer(status, "rpc ${error.text("code")}", error.text("message"), text, false)
            refused -> Answer(status, "tool error", result?.toolText(), text, false)
            else -> Answer(status, null, null, text)
        }
    }

    // The answer's JSON object; null for any other body (a list, an HTML page, nothing).
    private fun objectOf(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.content

    private fun JsonObject.toolText(): String? =
        ((get("content") as? JsonArray)?.firstOrNull() as? JsonObject)?.text("text")

    private fun Answer.describe(): String =
        "$status${type?.let { " $it" }.orEmpty()}${message?.let { " \"${it.take(MESSAGE_SHOWN)}\"" }.orEmpty()}"

    // Fills one request's path or body with B's values, or with their lookalikes drawn once in [twins] when given; a
    // fresh value is drawn each time.
    private class Filler(private val values: Map<Seed, String>, private val twins: MutableMap<String, String>?) {
        val sent = mutableSetOf<String>()
        var complete = true
            private set

        fun value(param: Param?): String? = when (param) {
            null -> null
            is Param.Fixed -> param.value
            is Param.Fresh -> Uuid.random().toString()
            is Param.Of -> values[param.seed]?.let { value -> twins?.getOrPut(value) { lookalike(value) } ?: value }
                ?.also { sent += it }
        }

        fun fill(text: String, pattern: Regex, param: (String) -> Param?): String = pattern.replace(text) { match ->
            value(param(match.groupValues[1])) ?: "".also { complete = false }
        }
    }

    private companion object {
        // `{id}`, `{id?}`, and `{...}` for a tail (Ktor writes a tailcard without its name).
        val PARAMETER = Regex("""\{([A-Za-z0-9_]+|\.\.\.)\??}""")
        val PLACEHOLDER = Regex("""\{\{([A-Z_]+)}}""")

        // Reads first, then deletions, updates and creations: a creation with B's name (an MCP server, a secret) makes
        // A's own resource of that name, which a later read or deletion would find while the lookalike finds nothing.
        val ORDER = listOf("GET", "DELETE", "PATCH", "POST", "PUT")

        /** How long a call may take: a stream or a hung route must not stop the matrix. */
        val CALL_TIMEOUT = 10.seconds

        /** How much of an error message a finding shows. */
        const val MESSAGE_SHOWN = 100

        /** The status of a call that did not answer in [CALL_TIMEOUT]. */
        const val NO_ANSWER = -1

        fun order(route: String): Int = ORDER.indexOf(route.substringBefore(' '))
    }
}
