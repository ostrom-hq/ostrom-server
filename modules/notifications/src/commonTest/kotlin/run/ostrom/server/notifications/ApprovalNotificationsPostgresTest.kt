// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.gates.GatesModule
import run.ostrom.server.gates.domain.models.AskedVia
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateNode
import run.ostrom.server.gates.domain.models.GatePush
import run.ostrom.server.gates.domain.models.PushRef
import run.ostrom.server.gates.domain.services.GateNodeResolver
import run.ostrom.server.gates.domain.services.GateReceiptSigner
import run.ostrom.server.gates.domain.usecases.AskGate
import run.ostrom.server.gates.domain.usecases.AskGateUseCase
import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.APP_REDIRECT_URI
import run.ostrom.server.identity.domain.ApiTokensRepository
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.MailSender
import run.ostrom.server.identity.domain.MobileCode
import run.ostrom.server.identity.domain.MobileCodesRepository
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.presentation.MobileTokenRequest
import run.ostrom.server.identity.presentation.MobileTokenResponse
import run.ostrom.server.identity.presentation.SignInConfig
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.auth.ORG_HEADER
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.notifications.domain.NotificationOutbox
import run.ostrom.server.notifications.domain.PUSH_TITLE
import run.ostrom.server.notifications.domain.PushDevicesRepository
import run.ostrom.server.notifications.domain.PushSender
import run.ostrom.server.notifications.presentation.PushDeviceDto
import run.ostrom.server.notifications.presentation.PushDeviceRequest
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A request for a yes, from the gates module to the approver's phones and mailbox, on the real identity, orgs, gates
 * and notifications modules and PostgreSQL: only the approver's live phones of the gate's organization hear of it,
 * a phone signed out of or whose token was revoked hears nothing, and a failing push service neither holds the request
 * nor stops the e-mail, and is tried again.
 */
class ApprovalNotificationsPostgresTest {
    private val modules = listOf(InstanceModule, IdentityModule, OrgsModule, GatesModule, NotificationsModule)
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
    private val push = RecordingPushSender()
    private val mail = RecordingMailSender()

    // The fakes of the providers, e-mail on as an instance with a mail provider, retries within the test, and the gate
    // node of every push: approved by `bob-s`.
    private val overrides = module {
        single<MailSender> { mail }
        single<PushSender> { push }
        single { SignInConfig.from(get()).copy(mailDirectory = "unused-by-the-fake") }
        single { NotificationOutbox(get(), get(), get(), listOf(200.milliseconds)) }
        single<GateNodeResolver> {
            object : GateNodeResolver {
                override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String) =
                    GateNode("ship", "g-main", "Push to main", listOf("bob-s"), enforced = true)
            }
        }
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
    }

    private suspend fun TestServer.bob(): User = koin.get<ResolveUserUseCase>()(
        VerifiedIdentity(IdentityProvider.GITHUB, "bob-s", "bob-s@example.com", true, "Bob", login = "bob-s"),
    )

    private suspend fun TestServer.orgOf(user: User, slug: String): Uuid {
        val org = Org(Uuid.random(), slug, slug, null, null, now)
        koin.get<OrgsRepository>().create(org, Member(Uuid.random(), org.id, user.id, OrgRole.MEMBER, now)) { false }
        return org.id
    }

    // The phone app's token, as the app gets it after the sign-in in the browser.
    private suspend fun TestServer.appToken(user: User, code: String): String {
        val hasher = koin.get<SecretHasher>()
        val challenge = hasher.pkceChallenge(VERIFIER)
        val issued = MobileCode(Uuid.random(), user.id, challenge, APP_REDIRECT_URI, now, now + 5.minutes)
        koin.get<MobileCodesRepository>().create(issued, hasher.hash(code))
        val exchanged = client.post("/api/v1/auth/mobile/token") {
            contentType(ContentType.Application.Json)
            setBody(MobileTokenRequest(code, VERIFIER, APP_REDIRECT_URI))
        }
        assertEquals(HttpStatusCode.OK, exchanged.status, exchanged.bodyAsText())
        return exchanged.body<MobileTokenResponse>().token
    }

    private suspend fun TestServer.registerPhone(token: String, org: Uuid, pushToken: String): String {
        val made = client.post("/api/v1/push-devices") {
            bearerAuth(token)
            header(ORG_HEADER, org.toString())
            contentType(ContentType.Application.Json)
            setBody(PushDeviceRequest(pushToken, "expo", "ios"))
        }
        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        return made.body<PushDeviceDto>().id
    }

    private suspend fun TestServer.askPushToMain(org: Uuid) = koin.get<AskGateUseCase>()(
        // A person asking from their workstation's hook: an agent token would need its session (gates' rule).
        GateActor.person("ana", org, OrgRole.MEMBER, now),
        AskGate(
            sessionId = null,
            runId = null,
            repo = "acme/app",
            realRepo = null,
            actionClass = "push-main",
            tool = "Bash",
            command = "git push origin main",
            harnessVersion = null,
            push = GatePush("origin", listOf(PushRef("refs/heads/main", COMMIT)), false),
            pullRequest = null,
            diff = "+ok",
            via = AskedVia.HOOK,
        ),
    )

    /** The rows of [org] holding [pushToken], read as the organization. */
    private suspend fun TestServer.phonesNamed(org: Uuid, pushToken: String): Int = driver.withOrganization(org) { tx ->
        tx.fetchAll(
            Statement.create("SELECT count(*) AS n FROM notifications_push_devices WHERE push_token = :t")
                .bind("t", pushToken),
        ).getOrThrow().first().get("n").asString().toInt()
    }

    // The outbox runs as the server runs it (NotificationsModule.start), until the test ends.
    private suspend fun TestServer.sending(block: suspend () -> Unit) {
        val scope = CoroutineScope(Job())
        NotificationsModule.start(scope, koin)
        try {
            block()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theApproverHearsOnTheirLivePhonesOfTheGatesOrganizationAndByEmailAndNoOtherPhone() =
        serverTest(modules, overrides) { server ->
            val bob = server.bob()
            val orgA = server.orgOf(bob, "bob-a")
            val orgB = server.orgOf(bob, "bob-b")
            val app = server.appToken(bob, CODE + "app")
            server.registerPhone(app, orgA, PHONE_A)
            server.registerPhone(app, orgB, PHONE_OTHER_ORG)
            val signedOut = server.appToken(bob, CODE + "signed-out")
            server.registerPhone(signedOut, orgA, PHONE_SIGNED_OUT)
            val logout = server.client.post("/api/v1/auth/logout") { bearerAuth(signedOut) }
            assertEquals(HttpStatusCode.NoContent, logout.status, logout.bodyAsText())
            // Revoked another way than a sign-out (the console, an expiry): the row stays, the phone hears nothing.
            val tokens = server.koin.get<ApiTokensRepository>()
            val before = tokens.listLive(bob.id, setOf(TokenKind.MOBILE), now).map { it.id }.toSet()
            val revoked = server.appToken(bob, CODE + "revoked")
            server.registerPhone(revoked, orgA, PHONE_REVOKED)
            val revokedId = tokens.listLive(bob.id, setOf(TokenKind.MOBILE), now).single { it.id !in before }.id
            assertTrue(tokens.revoke(bob.id, revokedId, now))
            assertEquals(0, server.phonesNamed(orgA, PHONE_SIGNED_OUT), "Signed out: forgotten")
            assertEquals(1, server.phonesNamed(orgA, PHONE_REVOKED), "Revoked: kept, never notified")

            server.sending {
                val asked = server.askPushToMain(orgA)
                val request = asked.requests.single()

                val sent = withTimeout(10.seconds) { push.delivered.receive() }
                assertEquals(listOf(PHONE_A), sent.map { it.to }, "The live phone of the gate's organization only")
                assertEquals(mapOf("kind" to "approval", "approvalId" to request.id.toString()), sent.single().data)
                assertEquals(PUSH_TITLE, sent.single().title)
                val email = withTimeout(10.seconds) { mail.delivered.receive() }
                assertEquals("bob-s@example.com", email.to)
                assertTrue(email.text.contains("/yes/${request.id}"), email.text)
                delay(500.milliseconds)
                assertEquals(1, push.calls.size, "One push for the request: ${push.calls}")
            }
        }

    @Test
    fun aFailingPushServiceNeitherHoldsTheRequestNorTheEmailAndIsTriedAgain() =
        serverTest(modules, overrides) { server ->
            push.failures = 1
            val bob = server.bob()
            val org = server.orgOf(bob, "bob-retry")
            server.registerPhone(server.appToken(bob, CODE + "retry"), org, PHONE_A)

            server.sending {
                val request = server.askPushToMain(org).requests.single()

                assertEquals("bob-s@example.com", withTimeout(10.seconds) { mail.delivered.receive() }.to)
                val sent = withTimeout(10.seconds) { push.delivered.receive() }
                assertEquals(request.id.toString(), sent.single().data["approvalId"])
                assertEquals(2, push.calls.size, "Refused once, then sent")
            }
        }

    @Test
    fun signingOutEverywhereForgetsThePhonesOfTheBrowserSessionsOnly() = serverTest(modules, overrides) { server ->
        val bob = server.bob()
        val org = server.orgOf(bob, "bob-browser")
        val app = server.appToken(bob, CODE + "kept")
        server.registerPhone(app, org, PHONE_A)
        val devices = server.koin.get<PushDevicesRepository>()
        assertTrue(devices.ofMember(org, Uuid.random()).isEmpty(), "Nobody else's phones")
        val mine = server.koin.get<OrgsRepository>().listOfUser(bob.id).single().member.id
        val phone = devices.ofMember(org, mine).single()
        devices.save(
            phone.copy(
                id = Uuid.random(),
                pushToken = PHONE_OTHER_ORG,
                credential = phone.credential?.copy(credentialId = Uuid.random(), browser = true),
            ),
            keep = 10,
        )
        // A phone of the first version, registered before the server kept its credential: never notified.
        devices.save(phone.copy(id = Uuid.random(), pushToken = PHONE_LEGACY, credential = null), keep = 10)
        val stored = devices.ofMember(org, mine).associate { it.pushToken to it.credential?.browser }
        assertEquals(mapOf(PHONE_A to false, PHONE_OTHER_ORG to true, PHONE_LEGACY to null), stored)

        devices.forget(org, bob.id, null)

        assertEquals(setOf(PHONE_A, PHONE_LEGACY), devices.ofMember(org, mine).map { it.pushToken }.toSet())
    }

    @Test
    fun aRegistrationWithoutCredentialKeepsThePhonesCredential() = serverTest(modules, overrides) { server ->
        val bob = server.bob()
        val org = server.orgOf(bob, "bob-again")
        server.registerPhone(server.appToken(bob, CODE + "again"), org, PHONE_A)
        val devices = server.koin.get<PushDevicesRepository>()
        val mine = server.koin.get<OrgsRepository>().listOfUser(bob.id).single().member.id
        val phone = devices.ofMember(org, mine).single()

        devices.save(phone.copy(id = Uuid.random(), credential = null), keep = 10)

        val again = devices.ofMember(org, mine).single()
        assertEquals(phone.credential, again.credential, "Still told: an older app or another caller unbinds nothing")
        assertEquals(phone.id, again.id)
    }

    private companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        const val CODE = "phone-code-0123456789abcdef0123456789abcdef-"
        const val VERIFIER = "phone-verifier-0123456789abcdef0123456789abcdef0123"
        const val PHONE_A = "ExponentPushToken[bob-phone-org-a]"
        const val PHONE_OTHER_ORG = "ExponentPushToken[bob-phone-org-b]"
        const val PHONE_SIGNED_OUT = "ExponentPushToken[bob-phone-signed-out]"
        const val PHONE_REVOKED = "ExponentPushToken[bob-phone-revoked]"
        const val PHONE_LEGACY = "ExponentPushToken[bob-phone-legacy]"
    }
}
