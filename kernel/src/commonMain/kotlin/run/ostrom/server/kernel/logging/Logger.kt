// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.logging

/** The severity of a log line, from the most verbose to the most severe. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Structured logs: one event per line with a message and named fields, never a formatted sentence to parse.
 * Secrets, tokens and request bodies are never passed as fields.
 */
interface Logger {
    /**
     * Writes an event if its level is enabled.
     *
     * @param level The severity.
     * @param message A short, constant description of the event ("request served", not "served /v1/x in 3 ms").
     * @param fields The variable parts of the event, by name.
     * @param error The failure that caused the event, if any; its type and message are logged, not its stack.
     */
    fun log(level: LogLevel, message: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null)

    /**
     * Returns a logger that adds the given fields to every event (the module name, a request id).
     *
     * @param fields The fields to add.
     *
     * @return The derived logger.
     */
    fun with(fields: Map<String, Any?>): Logger

    /** Writes a DEBUG event. */
    fun debug(message: String, fields: Map<String, Any?> = emptyMap()) = log(LogLevel.DEBUG, message, fields)

    /** Writes an INFO event. */
    fun info(message: String, fields: Map<String, Any?> = emptyMap()) = log(LogLevel.INFO, message, fields)

    /** Writes a WARN event. */
    fun warn(message: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null) =
        log(LogLevel.WARN, message, fields, error)

    /** Writes an ERROR event. */
    fun error(message: String, fields: Map<String, Any?> = emptyMap(), error: Throwable? = null) =
        log(LogLevel.ERROR, message, fields, error)
}
