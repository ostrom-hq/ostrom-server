// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity

import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.BootstrapInstanceUseCase
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.SignUpGrant
import run.ostrom.server.identity.domain.SignUpPolicy
import run.ostrom.server.identity.domain.StartWebSessionUseCase
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.fakes.RecordingAuditLog
import run.ostrom.server.identity.infrastructure.IdentityInstanceAuditTrail
import run.ostrom.server.identity.presentation.CreateTokenRequest
import run.ostrom.server.identity.presentation.CreatedTokenResponse
import run.ostrom.server.identity.presentation.MeResponse
import run.ostrom.server.kernel.settings.InstanceAccount
import run.ostrom.server.kernel.settings.InstanceAccounts
import run.ostrom.server.kernel.settings.InstanceAdministrators
import run.ostrom.server.kernel.settings.InstanceAuditRecord
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** What the identity module gives the instance's administration: its accounts, its audit chain, `/me`'s flag. */
class InstanceAdministrationTest {
    @Test
    fun anInstanceRecordGoesToTheInstanceChainAndSaysTheServerActedWhenNobodyDid() = runTest {
        val log = RecordingAuditLog()
        val trail = IdentityInstanceAuditTrail(log)
        val alice = Uuid.random()

        trail.record(InstanceAuditRecord(alice, "instance.settings.saved", "settings", "mail", mapOf("version" to "1")))
        trail.record(InstanceAuditRecord(null, "instance.admin.setup", "instance_admin", alice.toString()))

        assertEquals(
            listOf(
                AuditEntry(null, alice, "instance.settings.saved", "settings", "mail", mapOf("version" to "1")),
                AuditEntry(
                    null,
                    null,
                    "instance.admin.setup",
                    "instance_admin",
                    alice.toString(),
                    mapOf("actor_kind" to "server"),
                ),
            ),
            log.entries,
        )
    }

    @Test
    fun theSetupLinksPersonAndTheAccountsAreFoundAndMeSaysWhoAdministers() = serverTest(
        listOf(IdentityModule),
        module { single<InstanceAdministrators> { InstanceAdministrators { true } } },
    ) { server ->
        val accounts = server.koin.get<InstanceAccounts>()
        assertTrue(accounts.setupOwners().isEmpty())
        val link = assertNotNull(server.koin.get<BootstrapInstanceUseCase>()())
        val ada = server.koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, "7", "Ada@Example.com", true, "Ada"),
            SignUpGrant.Presented(link.token),
        )
        // Already spent by the sign-up itself: the person is recorded once.
        server.koin.get<SignUpPolicy>().claim(SignUpGrant.Presented(link.token), ada.id)

        assertEquals(setOf(ada.id), accounts.setupOwners().keys)
        val account = InstanceAccount(ada.id, ada.login, ada.primaryEmail)
        assertEquals(account, accounts.byVerifiedEmail(" ADA@example.com "))
        assertNull(accounts.byVerifiedEmail("someone@example.com"))
        assertEquals(mapOf(ada.id to account), accounts.describe(listOf(ada.id, Uuid.random())))
        assertEquals(emptyMap(), accounts.describe(emptyList()))
        server.koin.get<InstanceAuditTrail>()
            .record(InstanceAuditRecord(null, "instance.admin.setup", "instance_admin", ada.id.toString()))

        val session = server.koin.get<StartWebSessionUseCase>()(ada.id)
        val me = server.client.get("/api/v1/auth/me") { browserSession(session.secret, null) }
        assertEquals(true, me.body<MeResponse>().instanceAdmin)
        val created = server.client.post("/api/v1/tokens") {
            browserSession(session.secret, session.csrfToken)
            contentType(ContentType.Application.Json)
            setBody(CreateTokenRequest("t", listOf("profile:read")))
        }.body<CreatedTokenResponse>()
        val agent = server.client.get("/api/v1/auth/me") { bearerAuth(created.secret) }
        assertNull(agent.body<MeResponse>().instanceAdmin, "A program's token never opens the Admin area")
    }
}
