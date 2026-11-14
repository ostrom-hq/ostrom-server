// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/Main.kt; see NOTICE.
package run.ostrom.server.push

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import me.nathanfallet.flareon.core.credentials.GoogleCredentials
import platform.posix.getenv

private const val DEFAULT_PORT = 8080

@OptIn(ExperimentalForeignApi::class)
private fun env(name: String): String? = getenv(name)?.toKString()?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Configuration, from the environment of the Cloud Run service: `PORT` (set by Cloud Run), `OSTROM_PUSH_PROJECT_ID`
 * (default: the project of the service account), `OSTROM_PUSH_BLOCKED_INSTANCES` (instance ids cut off, comma
 * separated) and the per-minute quotas `OSTROM_PUSH_LIMIT_INSTANCE`, `OSTROM_PUSH_LIMIT_TOKEN` and
 * `OSTROM_PUSH_LIMIT_TOTAL`. Credentials come from the metadata server: no key file.
 */
fun main() {
    val port = env("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val defaults = RelayLimits()
    val limits = RelayLimits(
        env("OSTROM_PUSH_LIMIT_INSTANCE")?.toIntOrNull() ?: defaults.perInstance,
        env("OSTROM_PUSH_LIMIT_TOKEN")?.toIntOrNull() ?: defaults.perToken,
        env("OSTROM_PUSH_LIMIT_TOTAL")?.toIntOrNull() ?: defaults.total,
    )
    val blocked = env("OSTROM_PUSH_BLOCKED_INSTANCES")
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    val fcm = FirebaseFcm(GoogleCredentials.fromMetadataServer(), projectId = env("OSTROM_PUSH_PROJECT_ID"))
    println("Ostrom Push relay on port $port, ${blocked.size} blocked instances.")
    embeddedServer(CIO, port = port) { relayModule(Relay(fcm, blocked, limits)) }.start(wait = true)
}
