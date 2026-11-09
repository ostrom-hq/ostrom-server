// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.logging

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock

/**
 * Writes each event as one JSON object per line (`ts`, `level`, `msg`, then the fields), the format log collectors
 * (Loki, Datadog, CloudWatch) ingest without a parser.
 *
 * @param minLevel The lowest level written.
 * @param clock The clock of the `ts` field, injected for tests.
 * @param sink Where each line goes; standard output by default.
 * @param context Fields added to every event.
 */
class JsonLogger(
    private val minLevel: LogLevel,
    private val clock: Clock = Clock.System,
    private val sink: (String) -> Unit = ::println,
    private val context: Map<String, Any?> = emptyMap(),
) : Logger {
    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        if (level < minLevel) return
        val content = LinkedHashMap<String, JsonElement>()
        content["ts"] = JsonPrimitive(clock.now().toString())
        content["level"] = JsonPrimitive(level.name)
        content["msg"] = JsonPrimitive(message)
        (context + fields).forEach { (key, value) -> content[key] = value.toJsonElement() }
        if (error != null) {
            content["error"] = JsonPrimitive(error::class.simpleName ?: "Throwable")
            error.message?.let { content["error_message"] = JsonPrimitive(it) }
        }
        sink(JsonObject(content).toString())
    }

    override fun with(fields: Map<String, Any?>): Logger = JsonLogger(minLevel, clock, sink, context + fields)

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }
}
