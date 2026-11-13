// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.core.api.models.Capabilities
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.SESSION_COOKIE
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.http.ApiError
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.kernel.http.configureHttp
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

class CapabilitiesTest {
    /** One credential of each kind the identity module resolves: none needs an organization or a scope here. */
    private class EveryKindAuthenticator : TokenAuthenticator {
        override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
            "cli-token" -> AuthenticatedPrincipal("user-1", PrincipalKind.USER, emptySet())
            "app-token" -> AuthenticatedPrincipal(
                "user-1",
                PrincipalKind.USER,
                emptySet(),
                channel = PrincipalChannel.MOBILE_APP,
            )
            "agent-token" -> AuthenticatedPrincipal("session-1", PrincipalKind.AGENT, setOf("agent"), orgId = ORG)
            "device-token" -> AuthenticatedPrincipal("device-1", PrincipalKind.DEVICE, emptySet())
            "service-key" -> AuthenticatedPrincipal("key-1", PrincipalKind.SERVICE, emptySet(), orgId = ORG)
            else -> null
        }

        override suspend fun authenticateSession(
            sessionToken: String,
            csrfToken: String?,
            requireCsrf: Boolean,
            origin: String?,
        ): AuthenticatedPrincipal? = if (sessionToken == "console-session") {
            AuthenticatedPrincipal("user-1", PrincipalKind.USER, emptySet(), channel = PrincipalChannel.BROWSER)
        } else {
            null
        }
    }

    /** A module serving fixed features. */
    private class FixedModule(override val name: String, private val served: Set<String>) : ServerModule {
        override val koinModule: Module = module {}

        override suspend fun features(koin: Koin): Set<String> = served
    }

    /** A module whose feature follows its configuration, read from Koin at each call. */
    private object ConfiguredModule : ServerModule {
        override val name: String = "configured"
        override val koinModule: Module = module {}

        override suspend fun features(koin: Koin): Set<String> =
            if (koin.getOrNull<String>(CONFIGURED) == "on") setOf("billing.v1") else emptySet()
    }

    /** A module that keeps the default: it serves no feature of its own. */
    private object PlainModule : ServerModule {
        override val name: String = "plain"
        override val koinModule: Module = module {}
    }

    private fun capabilitiesServer(
        modules: List<ServerModule>,
        configured: String? = null,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        val koin = koinApplication {
            modules(
                module {
                    single<Logger> { JsonLogger(LogLevel.ERROR, sink = {}) }
                    single<TokenAuthenticator> { EveryKindAuthenticator() }
                    configured?.let { value -> single(CONFIGURED) { value } }
                },
            )
        }.koin
        application { configureHttp(koin, modules) }
        block()
    }

    private suspend fun HttpResponse.capabilities(): Capabilities = OstromJson.decodeFromString(bodyAsText())

    @Test
    fun aTokenReadsTheApiVersionTheBuildVersionAndTheSortedUnionOfEveryModulesFeatures() = capabilitiesServer(
        listOf(
            FixedModule("sessions", setOf("sessions.v1", "gates.v1")),
            FixedModule("gates", setOf("gates.v1", "audit.v1")),
            PlainModule,
        ),
    ) {
        val response = client.get("/api/v1/capabilities") { bearerAuth("cli-token") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            Capabilities(1, SERVER_VERSION, listOf("audit.v1", "gates.v1", "sessions.v1")),
            response.capabilities(),
            "Each key once, sorted; the version is the build's",
        )
    }

    @Test
    fun aServerWhoseModulesServeNoFeatureAnswersAnEmptyList() = capabilitiesServer(listOf(PlainModule)) {
        val response = client.get("/api/v1/capabilities") { bearerAuth("cli-token") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(Capabilities(1, SERVER_VERSION, emptyList()), response.capabilities())
    }

    @Test
    fun everyKindOfCredentialReadsThemWithoutAnOrganizationOrAScope() = capabilitiesServer(
        listOf(FixedModule("sessions", setOf("sessions.v1"))),
    ) {
        for (token in listOf("cli-token", "app-token", "agent-token", "device-token", "service-key")) {
            val response = client.get("/api/v1/capabilities") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status, "$token reads the capabilities")
            assertEquals(listOf("sessions.v1"), response.capabilities().features)
        }
        val session = client.get("/api/v1/capabilities") { cookie(SESSION_COOKIE, "console-session") }
        assertEquals(HttpStatusCode.OK, session.status, "A console session reads them too")
        assertEquals(listOf("sessions.v1"), session.capabilities().features)
    }

    @Test
    fun anAnonymousCallIsRefusedWith401Unauthorized() = capabilitiesServer(listOf(PlainModule)) {
        val response = client.get("/api/v1/capabilities")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("unauthorized", OstromJson.decodeFromString<ApiError>(response.bodyAsText()).error.type)
    }

    @Test
    fun anUnknownTokenIsRefusedWith401Unauthorized() = capabilitiesServer(listOf(PlainModule)) {
        val response = client.get("/api/v1/capabilities") { bearerAuth("revoked-token") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("unauthorized", OstromJson.decodeFromString<ApiError>(response.bodyAsText()).error.type)
    }

    @Test
    fun aFeatureIsListedOnlyWhenItsModuleIsConfigured() {
        capabilitiesServer(listOf(ConfiguredModule)) {
            val response = client.get("/api/v1/capabilities") { bearerAuth("cli-token") }
            assertEquals(emptyList(), response.capabilities().features, "Not configured: not listed")
        }
        capabilitiesServer(listOf(ConfiguredModule), configured = "on") {
            val response = client.get("/api/v1/capabilities") { bearerAuth("cli-token") }
            assertEquals(listOf("billing.v1"), response.capabilities().features, "Configured: listed")
        }
    }

    private companion object {
        const val ORG = "6f1c2a3e-0000-4000-8000-000000000001"
        val CONFIGURED = named("configured")
    }
}
