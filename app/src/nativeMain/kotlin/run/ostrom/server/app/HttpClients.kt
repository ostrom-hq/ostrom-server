// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.app

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.setenv

/** How long, in milliseconds, the Curl engine waits in one poll while exchanges are open. */
internal const val CURL_POLL_MILLIS = 5

/**
 * Sets up the HTTP clients of the process (Ktor's Curl engine) before any of them is created. Called first by
 * [main].
 *
 * Ktor's Curl engine hands a finished exchange back only after its next `curl_multi_poll`, which, while any other
 * exchange of the same client is open (a model's stream, a slow MCP server), waits for that exchange's socket or for
 * its timeout: 100 ms by default, `KTOR_CURL_POLL_TIMEOUT`, read once per process (Ktor 3.6.1,
 * `CurlMultiApiHandler.perform`). Each exchange then cost up to 100 ms: an MCP tool call of a server that answers at
 * once (4 exchanges) took 315 ms, over the 250 ms of the load pass (chart-load 41868912529). Polling every
 * [CURL_POLL_MILLIS] bounds that wait; the engine's thread wakes that often only while an exchange is open. A value
 * the operator set is kept.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun configureHttpClients() {
    check(setenv(CURL_POLL_TIMEOUT, CURL_POLL_MILLIS.toString(), 0) == 0) { "$CURL_POLL_TIMEOUT could not be set." }
}

private const val CURL_POLL_TIMEOUT = "KTOR_CURL_POLL_TIMEOUT"
