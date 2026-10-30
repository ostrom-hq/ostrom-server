package dev.ostrom.server.kernel.http

import dev.ostrom.server.kernel.auth.token
import dev.ostrom.core.telemetry.Telemetry
import dev.ostrom.server.kernel.module.ServerModule
import dev.ostrom.server.kernel.observability.OstromMetrics
import dev.ostrom.server.kernel.observability.installTelemetry
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
 * Installs the kernel's HTTP layer (traces and request metrics when the Koin has a [Telemetry], JSON, type-safe
 * resources, request ids, request logs, uniform errors, token authentication, `/health`) and the routes of every
 * module.
 *
 * @param koin The application's Koin, with the kernel module and every module's definitions loaded.
 * @param modules The functional modules, in order.
 */
fun Application.configureHttp(koin: Koin, modules: List<ServerModule>) {
    val logger = koin.get<dev.ostrom.server.kernel.logging.Logger>()
    koin.getOrNull<Telemetry>()?.let { installTelemetry(it, koin.get<OstromMetrics>()) }
    install(ContentNegotiation) { json(OstromJson) }
    install(Resources)
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { Uuid.random().toHexString() }
        verify { it.length in 1..128 && it.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' } }
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(RequestLogging) { this.logger = logger }
    configureErrors(logger)
    install(Authentication) { token(koin.get()) }
    routing {
        healthRoutes(koin.getAll(), SERVER_VERSION)
        modules.forEach { it.routes(this, koin) }
    }
}
