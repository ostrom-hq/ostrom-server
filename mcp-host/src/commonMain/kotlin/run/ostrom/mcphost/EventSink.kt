// SPDX-License-Identifier: Apache-2.0
package run.ostrom.mcphost

import kotlinx.serialization.json.JsonObject

/**
 * Where the host's events go: one JSON line each (`started`, `exited`, `killed`, `oversize`, `backoff`,
 * `start_failed`), with the key's first 12 characters, the launcher and the package; never arguments, environment,
 * input, output or stderr.
 */
fun interface EventSink {
    /**
     * Emits one event.
     *
     * @param event The event.
     */
    fun emit(event: JsonObject)
}
