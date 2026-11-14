// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.hooks.fakes

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

/** An answer: its status and its JSON body (null when it has none). */
class Answer(val status: Int, val body: JsonElement?) {
    /** The object answered. */
    val json: JsonObject get() = body!!.jsonObject

    /** The `error.type` of a refusal. */
    val error: String? get() = (body as? JsonObject)?.get("error")?.jsonObject?.get("type")?.jsonPrimitive?.content

    /** A string field, by a dotted path. */
    fun text(path: String): String? = at(path)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

    /** The items of a list, by a dotted path (`items` by default). */
    fun list(path: String = "items"): JsonArray = at(path)!!.jsonArray

    private fun at(path: String): JsonElement? = path.split('.').fold(body) { element, key ->
        when (element) {
            is JsonObject -> element[key]
            is JsonArray -> key.toIntOrNull()?.let { element.getOrNull(it) }
            else -> null
        }
    }

    override fun toString(): String = "$status $body"
}

/** Sends [body] (JSON) to [path] as [token]. */
suspend fun HttpClient.call(method: HttpMethod, token: String, path: String, body: String? = null): Answer {
    val response: HttpResponse = request(path) {
        this.method = method
        bearerAuth(token)
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }
    val text = response.bodyAsText()
    return Answer(response.status.value, text.takeIf { it.isNotBlank() }?.let(Json::parseToJsonElement))
}

/** A file of a publication: its path, its text, and its mode when given. */
fun upload(path: String, text: String, mode: String? = null): String {
    val content = Base64.Default.encode(text.encodeToByteArray())
    return """{"path": "$path", "contentBase64": "$content"${mode?.let { """, "mode": "$it"""" }.orEmpty()}}"""
}

/** The hook definition of the tests: run.sh on Stop, with [extra] JSON fields. */
fun definition(events: String = """["Stop"]""", command: String = "run.sh", extra: String = ""): String =
    """{"events": $events, "command": "$command"$extra}"""

/** The executable file of the tests' hooks. */
val SCRIPT: String = upload("run.sh", "#!/bin/sh\n", "executable")

/** A publication's body. */
fun publication(
    version: String,
    vararg files: String = arrayOf(SCRIPT),
    definition: String = definition(),
    allMembers: Boolean? = null,
): String {
    val audience = allMembers?.let { """, "allMembers": $it""" }.orEmpty()
    return """{"version": "$version", "definition": $definition, "files": [${files.joinToString()}]$audience}"""
}

/** A string field of a JSON object. */
fun JsonElement.field(name: String): String = jsonObject.getValue(name).jsonPrimitive.content
