// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

/**
 * Sets up the HTTP clients of the process (Ktor's Curl engine) before any of them is created. Called first by
 * [main].
 */
internal fun configureHttpClients() {
    // Red first: nothing set yet.
}
