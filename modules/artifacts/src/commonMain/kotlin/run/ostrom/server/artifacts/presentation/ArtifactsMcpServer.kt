// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.presentation

import run.ostrom.server.artifacts.domain.ArtifactCaller
import run.ostrom.server.artifacts.domain.ArtifactRules
import run.ostrom.server.kernel.errors.OstromException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The artifacts MCP server (protocol 2025-06-18, streamable HTTP with JSON answers, no server-sent events): the
 * tools `publish`, `list`, `read` and `share`, acting for the member whose credential calls it. The CLI connects
 * agents to it, so an agent publishes its report and gets the link to give the member.
 *
 * @param dependencies The artifact use cases.
 */
internal class ArtifactsMcpServer(private val dependencies: ArtifactsRoutesDependencies) {
    /**
     * Answers one JSON-RPC message.
     *
     * @param body The request body.
     * @param callerFor The member for a scope ([ARTIFACTS_READ] or [ARTIFACTS_WRITE]); throws when refused.
     *
     * @return The answer, or null for a notification (HTTP 202 without a body).
     */
    suspend fun handle(body: String, callerFor: suspend (String) -> ArtifactCaller): JsonObject? {
        val message = try {
            JSON.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            return error(JsonNull, PARSE_ERROR, "Parse error.")
        }
        val method = (message?.get("method") as? JsonPrimitive)?.contentOrNull
        val id = message?.get("id")
        return when {
            method == null -> error(id ?: JsonNull, INVALID_REQUEST, "Invalid request.")
            id == null -> null
            else -> answer(id, method, message["params"] as? JsonObject ?: JsonObject(emptyMap()), callerFor)
        }
    }

    private suspend fun answer(
        id: JsonElement,
        method: String,
        params: JsonObject,
        callerFor: suspend (String) -> ArtifactCaller,
    ): JsonObject = when (method) {
        "initialize" -> result(id, initialize(params))
        "ping" -> result(id, JsonObject(emptyMap()))
        "tools/list" -> result(id, buildJsonObject { put("tools", TOOLS) })
        "tools/call" -> {
            val name = (params["name"] as? JsonPrimitive)?.contentOrNull
            val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
            if (name == null || name !in TOOL_NAMES) {
                error(id, INVALID_PARAMS, "Unknown tool: ${name.orEmpty().take(MAX_ECHO)}.")
            } else {
                call(id, name, arguments, callerFor)
            }
        }
        else -> error(id, METHOD_NOT_FOUND, "Method not found.")
    }

    private suspend fun call(
        id: JsonElement,
        name: String,
        arguments: JsonObject,
        callerFor: suspend (String) -> ArtifactCaller,
    ): JsonObject = try {
        val text = when (name) {
            "publish" -> publish(callerFor(ARTIFACTS_WRITE), JSON.decodeFromJsonElement<PublishArguments>(arguments))
            "list" -> list(callerFor(ARTIFACTS_READ))
            "read" -> read(callerFor(ARTIFACTS_READ), JSON.decodeFromJsonElement<ReadArguments>(arguments))
            else -> share(callerFor(ARTIFACTS_WRITE), JSON.decodeFromJsonElement<ShareArguments>(arguments))
        }
        result(id, toolResult(text, isError = false))
    } catch (failure: OstromException) {
        result(id, toolResult(failure.message, isError = true))
    } catch (_: SerializationException) {
        error(id, INVALID_PARAMS, "Invalid arguments for $name: see its input schema.")
    }

    private suspend fun publish(caller: ArtifactCaller, arguments: PublishArguments): String = with(dependencies) {
        val files = arguments.files.toUploads()
        if (arguments.artifact == null) {
            val artifact = publish.create(caller, arguments.title.orEmpty(), arguments.access ?: "personal", files)
            "Published \"${artifact.title}\" (version 1, ${artifact.access.wire}): ${toResponse(artifact).url}\n" +
                "Artifact id: ${artifact.id}"
        } else {
            val id = artifactId(arguments.artifact)
            val version = publish.addVersion(caller, id, files)
            "Published version ${version.number} of ${arguments.artifact}: ${toResponse(read.get(caller, id)).url}"
        }
    }

    private suspend fun list(caller: ArtifactCaller): String = with(dependencies) {
        val artifacts = read.list(caller, null, LIST_LIMIT)
        if (artifacts.isEmpty()) return "No artifacts yet."
        artifacts.joinToString("\n") {
            "${it.id}  ${it.title}  (${it.access.wire}, version ${it.latestVersion})  ${toResponse(it).url}"
        }
    }

    private suspend fun read(caller: ArtifactCaller, arguments: ReadArguments): String {
        val file = dependencies.read.file(caller, artifactId(arguments.artifact), arguments.version, arguments.path)
        val textual = file.bytes.none { it == NUL } && !ArtifactRules.contentType(arguments.path.orEmpty())
            .let { it.startsWith("image/") || it.startsWith("font/") }
        return if (textual) file.bytes.decodeToString() else "A binary file of ${file.bytes.size} bytes."
    }

    private suspend fun share(caller: ArtifactCaller, arguments: ShareArguments): String = with(dependencies) {
        val id = artifactId(arguments.artifact)
        arguments.member?.let { manage.share(caller, id, it, share = !arguments.remove) }
        val artifact = manage.update(caller, id, null, arguments.access)
        val link = view.publicUrl(artifact)?.let { "\nPublic link: $it" }.orEmpty()
        "\"${artifact.title}\" is ${artifact.access.wire}: ${toResponse(artifact).url}$link"
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", requested?.takeIf { it in VERSIONS } ?: VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            putJsonObject("serverInfo") {
                put("name", "ostrom-artifacts")
                put("version", "1")
            }
            put("instructions", INSTRUCTIONS)
        }
    }

    @Serializable
    private data class PublishArguments(
        val title: String? = null,
        val files: List<FileDto>,
        val access: String? = null,
        val artifact: String? = null,
    )

    @Serializable
    private data class ReadArguments(val artifact: String, val version: Int? = null, val path: String? = null)

    @Serializable
    private data class ShareArguments(
        val artifact: String,
        val access: String? = null,
        val member: String? = null,
        val remove: Boolean = false,
    )

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val MAX_ECHO = 64
        const val LIST_LIMIT = 50
        const val NUL: Byte = 0
        private val VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        private val TOOL_NAMES = setOf("publish", "list", "read", "share")

        const val INSTRUCTIONS = "Publish pages (HTML or Markdown, with their images, CSS, JS and data files) for " +
            "your organization to read. publish returns the address to give the member; publish again with the " +
            "same artifact id to add a version. The page runs sandboxed: scripts only from its files, inline, " +
            "cdnjs.cloudflare.com, cdn.jsdelivr.net/npm/, unpkg.com, cdn.tailwindcss.com or code.jquery.com; " +
            "styles from its files, inline or Google Fonts; images, media and fetch() only from its own files " +
            "(relative URLs) or data:/blob: URLs; no forms, frames, cookies or storage. Publish every file the " +
            "page uses. Never put a secret in a page: a file holding a key or a token is refused."

        private val TOOLS: JsonArray = buildJsonArray {
            add(tool("publish", "Publish an artifact, or a new version of one (artifact).", PUBLISH_SCHEMA))
            add(tool("list", "List the artifacts you can read.", buildJsonObject { put("type", "object") }))
            add(tool("read", "Read a file of an artifact (the entry page by default).", READ_SCHEMA))
            add(tool("share", "Change who reads an artifact: access, or a member to add or remove.", SHARE_SCHEMA))
        }

        private val PUBLISH_SCHEMA get() = schema(
            required = listOf("files"),
            "title" to string("The title (required for a new artifact)."),
            "access" to string("personal (default), team or link."),
            "artifact" to string("An artifact id, to publish a new version of it."),
            "files" to buildJsonObject {
                put("type", "array")
                put(
                    "items",
                    schema(
                        required = listOf("path", "content"),
                        "path" to string("A relative path: index.html, img/a.png."),
                        "content" to string("The content: text, or base64 with encoding base64."),
                        "encoding" to string("utf8 (default) or base64."),
                    ),
                )
            },
        )

        private val READ_SCHEMA get() = schema(
            required = listOf("artifact"),
            "artifact" to string("The artifact id."),
            "version" to buildJsonObject { put("type", "integer") },
            "path" to string("The file; the entry page by default."),
        )

        private val SHARE_SCHEMA get() = schema(
            required = listOf("artifact"),
            "artifact" to string("The artifact id."),
            "access" to string("personal, team or link."),
            "member" to string("A member id to share with."),
            "remove" to buildJsonObject { put("type", "boolean") },
        )

        private fun toolResult(text: String, isError: Boolean) = buildJsonObject {
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            }
            put("isError", isError)
        }

        private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

        private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }

        private fun tool(name: String, description: String, input: JsonObject) = buildJsonObject {
            put("name", name)
            put("description", description)
            put("inputSchema", input)
        }

        private fun string(description: String) = buildJsonObject {
            put("type", "string")
            put("description", description)
        }

        private fun schema(required: List<String>, vararg properties: Pair<String, JsonObject>) = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { properties.forEach { (name, value) -> put(name, value) } }
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }
    }
}
