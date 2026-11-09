// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.http

import run.ostrom.core.api.models.Capabilities
import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.module.ServerModule
import io.ktor.resources.Resource
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.koin.core.Koin

/** `GET /api/v1/capabilities`: the API version this server speaks, its version and the features it serves. */
@Serializable
@Resource("/api/v1/capabilities")
class CapabilitiesApi

/**
 * The capabilities route, served by the kernel because only the kernel holds the list of modules. Any credential
 * passes (a person's token or console session, the app's, an agent's, a service key): it names no organization and
 * asks no scope. An anonymous call gets the 401 `unauthorized` of every protected route, which tells a client it
 * reached an Ostrom server and should sign in next.
 *
 * @param koin Resolves what each module reads to know whether its features are configured.
 * @param modules The functional modules, whose [ServerModule.features] make up the answer's.
 * @param version The server version (the build's, `SERVER_VERSION` of the generated BuildInfo.kt).
 */
internal fun Route.capabilitiesRoutes(koin: Koin, modules: List<ServerModule>, version: String) {
    authenticated {
        get<CapabilitiesApi> {
            call.respond(serverCapabilities(koin, modules, version))
        }
    }
}

/**
 * What this server speaks and serves: the contract version of the ostrom-core it is built on, its version, and the
 * union of every module's features, sorted.
 *
 * @param koin Resolves the modules' configuration.
 * @param modules The functional modules.
 * @param version The server version.
 *
 * @return The capabilities.
 */
internal suspend fun serverCapabilities(koin: Koin, modules: List<ServerModule>, version: String): Capabilities =
    Capabilities(
        apiVersion = ServerFeatures.API_VERSION,
        serverVersion = version,
        features = modules.flatMap { it.features(koin) }.distinct().sorted(),
    )
