// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.reports

import run.ostrom.server.budgets.BudgetsModule
import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.APP_REDIRECT_URI
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.MobileCode
import run.ostrom.server.identity.domain.MobileCodesRepository
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.presentation.MobileTokenRequest
import run.ostrom.server.identity.presentation.MobileTokenResponse
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.reports.presentation.ActivityPageDto
import run.ostrom.server.reports.presentation.SpendingDto
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.sessions.SessionsModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.UsageModule
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The phone app's token on the real identity, orgs and reports modules and PostgreSQL: it reads what the app shows,
 * its organization's activity feed (step 6.3), spending and receipts, with the scopes of the token itself.
 */
class PhoneTokenReportsPostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        SecretsModule, UsageModule, BudgetsModule, ReportsModule,
    )
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /** A person, the only member of a new organization, signed in on the phone: the app's token. */
    private suspend fun TestServer.phoneToken(login: String): String {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        koin.get<OrgsRepository>().create(org, Member(Uuid.random(), org.id, user.id, OrgRole.MEMBER, now)) { false }
        // The one-time code the sign-in in the browser gives the app, then the app's own exchange.
        val hasher = koin.get<SecretHasher>()
        val challenge = hasher.pkceChallenge(VERIFIER)
        val code = MobileCode(Uuid.random(), user.id, challenge, APP_REDIRECT_URI, now, now + 5.minutes)
        koin.get<MobileCodesRepository>().create(code, hasher.hash(CODE))
        val exchanged = client.post("/api/v1/auth/mobile/token") {
            contentType(ContentType.Application.Json)
            setBody(MobileTokenRequest(CODE, VERIFIER, APP_REDIRECT_URI))
        }
        assertEquals(HttpStatusCode.OK, exchanged.status, exchanged.bodyAsText())
        return exchanged.body<MobileTokenResponse>().token
    }

    @Test
    fun thePhonesTokenReadsItsOrganizationsActivitySpendingAndReceipts() = serverTest(modules) { server ->
        val token = server.phoneToken("mia")
        assertTrue(token.startsWith("osm_"), "The app's token")

        val activity = server.client.get("/api/v1/activity") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, activity.status, activity.bodyAsText())
        assertEquals(emptyList(), activity.body<ActivityPageDto>().items, "A new organization's feed")
        val spending = server.client.get("/api/v1/spending?period=week") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, spending.status, spending.bodyAsText())
        assertEquals("week", spending.body<SpendingDto>().period)
        val receipts = server.client.get("/api/v1/receipts") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, receipts.status, receipts.bodyAsText())
    }

    private companion object {
        const val CODE = "phone-code-0123456789abcdef0123456789abcdef"
        const val VERIFIER = "phone-verifier-0123456789abcdef0123456789abcdef0123"
    }
}
