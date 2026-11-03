// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app.isolation

import dev.ostrom.server.testing.TestServer
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Seeds a [party] through the API as its people and tokens do. A step that fails is written in the party's problems
 * and its value left out, so one run of the matrix reports every step to fix at once (the routes that name the value
 * are reported as not attacked).
 */
internal class Seeder(private val server: TestServer, private val party: Party) {
    /**
     * Sends a seeding request and returns its JSON answer, or null (and a problem) when the status is not [expected].
     *
     * @param step What the request makes, for the report.
     */
    suspend fun send(
        step: String,
        route: String,
        credential: Credential,
        body: String? = null,
        expected: Int = CREATED,
    ): JsonElement? {
        val response = server.client.request(route.substringAfter(' ')) {
            method = HttpMethod.parse(route.substringBefore(' '))
            credential.apply(this)
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }
        val text = response.bodyAsText()
        if (response.status.value != expected) {
            party.problems += "seed ${party.marker} $step: $route answers ${response.status.value}, not $expected: " +
                text.take(PROBLEM_LENGTH)
            return null
        }
        return try {
            if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text)
        } catch (_: IllegalArgumentException) {
            JsonPrimitive(text)
        }
    }

    /** Keeps [seed]'s value, read at [path] (`a.b.0.c`) of [answer]; a missing one is a problem of [step]. */
    fun keep(seed: Seed, answer: JsonElement?, path: String, step: String = seed.name): String? {
        if (answer == null) return null
        val value = answer.at(path)
        if (value == null) {
            party.problems += "seed ${party.marker} $step: no `$path` in ${answer.toString().take(PROBLEM_LENGTH)}"
        } else {
            party.values[seed] = value
        }
        return value
    }

    /** Keeps [value] as [seed]'s, when the step that made it succeeded. */
    fun keep(seed: Seed, value: String, answer: JsonElement?) {
        if (answer != null) party.values[seed] = value
    }

    /** Writes a problem that is not an answer's. */
    fun problem(step: String, what: String) {
        party.problems += "seed ${party.marker} $step: $what"
    }

    private companion object {
        const val CREATED = 201
        const val PROBLEM_LENGTH = 300
    }
}

/** The string at [path] in a JSON tree: object keys and list indexes, separated by dots. */
internal fun JsonElement.at(path: String): String? {
    var node: JsonElement? = this
    for (key in path.split('.')) {
        node = when (val current = node) {
            is JsonObject -> current[key]
            is JsonArray -> key.toIntOrNull()?.let { current.getOrNull(it) }
            else -> null
        }
    }
    return (node as? JsonPrimitive)?.content
}
