// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.http

import dev.ostrom.server.kernel.auth.token
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.module.ServerModule
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.koin.core.Koin
import kotlin.uuid.Uuid

/** The JSON settings of every request and answer. */
val OstromJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** The server version, reported by `/health`. */
const val SERVER_VERSION: String = "0.1.0-dev"

/**
 * Installs the kernel's HTTP layer (request body limit, JSON, type-safe resources, request ids, request logs by route
 * template, uniform errors, token authentication, `/health`) and the routes of every module.
 *
 * @param koin The application's Koin, with the kernel module and every module's definitions loaded.
 * @param modules The functional modules, in order.
 */
fun Application.configureHttp(koin: Koin, modules: List<ServerModule>) {
    val logger = koin.get<Logger>()
    install(BodyLimit)
    install(ContentNegotiation) { json(OstromJson) }
    install(Resources)
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { Uuid.random().toHexString() }
        verify(::isValidRequestId)
        replyToHeader(HttpHeaders.XRequestId)
    }
    recordRouteTemplates()
    install(RequestLogging) { this.logger = logger }
    configureErrors(logger)
    install(Authentication) { token(koin.get()) }
    routing {
        healthRoutes(koin.getAll(), SERVER_VERSION)
        modules.forEach { it.routes(this, koin) }
    }
}

private const val MAX_REQUEST_ID_LENGTH = 128

/**
 * Whether a client's `X-Request-Id` is kept: 1 to 128 letters, digits, `-` or `_`. Any other value is replaced by
 * a generated id, so that a request id never carries text into the logs.
 *
 * @param id The header value.
 *
 * @return True when the id is kept.
 */
internal fun isValidRequestId(id: String): Boolean =
    id.isNotEmpty() && id.length <= MAX_REQUEST_ID_LENGTH && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
