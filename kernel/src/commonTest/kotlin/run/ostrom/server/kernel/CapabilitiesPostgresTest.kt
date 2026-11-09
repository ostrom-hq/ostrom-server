// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import run.ostrom.core.api.models.Capabilities
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `GET /api/v1/capabilities` on a real server started as a deployment starts: its answers go through the
 * ContractObserver, which checks them against ostrom-core's contract once the operation is `served`.
 */
class CapabilitiesPostgresTest {
    private class OneTokenAuthenticator : TokenAuthenticator {
        override suspend fun authenticate(token: String): AuthenticatedPrincipal? =
            if (token == "good-token") AuthenticatedPrincipal("user-1", PrincipalKind.USER, emptySet()) else null
    }

    private object SessionsLikeModule : ServerModule {
        override val name: String = "sessionslike"
        override val koinModule: Module = module {}

        override suspend fun features(koin: Koin): Set<String> = setOf("sessions.v1", "gates.v1")
    }

    private val overrides = module { single<TokenAuthenticator> { OneTokenAuthenticator() } }

    @Test
    fun aSignedInClientReadsTheCapabilitiesAsTheContractDeclaresThem() =
        serverTest(listOf(SessionsLikeModule), overrides) { server ->
            val response = server.client.get("/api/v1/capabilities") { bearerAuth("good-token") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(Capabilities(1, SERVER_VERSION, listOf("gates.v1", "sessions.v1")), response.body())
        }

    @Test
    fun anAnonymousClientIsToldToSignIn() = serverTest(listOf(SessionsLikeModule), overrides) { server ->
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/capabilities").status)
    }
}
