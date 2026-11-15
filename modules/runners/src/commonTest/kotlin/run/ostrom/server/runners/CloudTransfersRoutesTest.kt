// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import run.ostrom.server.testing.Contract
import run.ostrom.server.testing.ContractObserver
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import run.ostrom.core.api.OnBehalfOf
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.http.configureHttp
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.ApiDocs
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.runners.domain.CloudTransfer
import run.ostrom.server.runners.domain.CloudTransfersUseCaseImpl
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ResolveCloudActorUseCaseImpl
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.TransferState
import run.ostrom.server.runners.domain.TransferRecipient
import run.ostrom.server.runners.fakes.FakeTransferRecipients
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.InMemoryCloudTransfersRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.SwitchedTransferPolicy
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.presentation.CloudTransfersRoutesDependencies
import run.ostrom.server.runners.presentation.cloudTransferOperations
import run.ostrom.server.runners.presentation.cloudTransfersRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The transfer routes over the real use cases: who may write, what a request must carry, how a refusal answers. */
class CloudTransfersRoutesTest {
    private val aliceId = Uuid.parse("00000000-0000-0000-0000-0000000000a1").toString()
    private val bobId = Uuid.parse("00000000-0000-0000-0000-0000000000b2").toString()
    private val carolId = Uuid.parse("00000000-0000-0000-0000-0000000000c3").toString()
    private val sessions = InMemoryCloudSessionsRepository()
    private val transfers = InMemoryCloudTransfersRepository(sessions)
    private val policy = SwitchedTransferPolicy()
    private val members = mapOf(
        "alice" to OrgPrincipal(ORG_A, Uuid.parse(aliceId), OrgRole.MEMBER),
        "bob" to OrgPrincipal(ORG_A, Uuid.parse(bobId), OrgRole.MEMBER),
        "carol" to OrgPrincipal(ORG_A, Uuid.parse(carolId), OrgRole.OWNER),
    )

    private val authenticator = object : TokenAuthenticator {
        override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
            "agent" -> AuthenticatedPrincipal("alice", PrincipalKind.AGENT, setOf("cloud:write"))
            "reader" -> AuthenticatedPrincipal("alice", PrincipalKind.USER, setOf("cloud:read"))
            "stranger" -> AuthenticatedPrincipal("stranger", PrincipalKind.USER, setOf("user:*"))
            "carol-cli" ->
                AuthenticatedPrincipal("carol", PrincipalKind.USER, setOf("user:*"), credentialId = "pat-carol")
            else -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("user:*"))
        }
    }

    private val resolver = object : PrincipalOrgResolver {
        override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
            members[principal.subjectId]
    }

    private val audit = mutableListOf<OrgAuditRecord>()
    private val recipients = FakeTransferRecipients(
        TransferRecipient(aliceId, "alice", false),
        TransferRecipient(bobId, "bob", false),
        TransferRecipient(carolId, "carol", true),
    )

    private val module = object : ServerModule {
        override val name: String = "transfers"
        override val koinModule = module {}

        // Its catalogue, so that every answer is checked against the generated document (ContractObserver).
        override val apiOperations: ApiCatalog.() -> Unit = { cloudTransferOperations() }

        override fun routes(route: io.ktor.server.routing.Route, koin: org.koin.core.Koin) {
            val use = CloudTransfersUseCaseImpl(
                sessions, transfers, recipients, policy, OrgAuditTrail { audit += it }, SettableClock(),
            )
            val actor = ResolveCloudActorUseCaseImpl(recipients)
            route.cloudTransfersRoutes(CloudTransfersRoutesDependencies(use, resolver, actor))
        }
    }

    private fun run(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val docs = ApiDocs()
        val koin = koinApplication {
            modules(
                module {
                    single<Logger> { JsonLogger(LogLevel.ERROR, sink = {}) }
                    single<Clock> { Clock.System }
                    single<TokenAuthenticator> { authenticator }
                    single { docs }
                },
            )
        }.koin
        // Every answer is checked against the server's generated document (ContractObserver).
        val observer = ContractObserver { Contract.of(docs) }
        application {
            install(observer.plugin)
            configureHttp(koin, listOf(module))
        }
        block()
        observer.finish()
    }

    private suspend fun HttpClient.send(
        path: String,
        token: String,
        key: String? = null,
        body: String? = null,
        contentType: ContentType = ContentType.Application.Json,
    ): HttpResponse = post(path) {
        header(HttpHeaders.Authorization, "Bearer $token")
        if (key != null) header("Idempotency-Key", key)
        onBehalfOf?.let { header(OnBehalfOf.HEADER, it) }
        if (body != null) {
            header(HttpHeaders.ContentType, contentType.toString())
            setBody(body)
        }
    }

    private suspend fun HttpClient.read(path: String, token: String): HttpResponse =
        get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

    /** The member the next writes name in `Ostrom-On-Behalf-Of`, or none. */
    private var onBehalfOf: String? = null

    /** A write sent with `Ostrom-On-Behalf-Of` naming [member]. */
    private suspend fun HttpClient.sendFor(
        member: String,
        path: String,
        token: String,
        key: String? = null,
        body: String? = null,
    ): HttpResponse =
        try {
            onBehalfOf = member
            send(path, token, key, body)
        } finally {
            onBehalfOf = null
        }

    private fun running(owner: String = aliceId) = session(state = SessionState.RUNNING, ownerId = owner)
        .copy(desiredState = DesiredState.RUNNING).also(sessions::put)

    private fun idOf(body: String): String = Regex("\"id\":\"([0-9a-f-]{36})\"").find(body)!!.groupValues[1]

    @Test
    fun anOfferNeedsAKeyAWellFormedRecipientAndAPersonSignedIn() = run {
        val target = running()
        val path = "/api/v1/cloud/sessions/${target.id}/transfer"
        val body = """{"toMemberId":"bob"}"""

        assertEquals(HttpStatusCode.BadRequest, client.send(path, "alice", null, body).status)
        assertEquals(HttpStatusCode.BadRequest, client.send(path, "alice", "not valid!", body).status)
        assertEquals(HttpStatusCode.BadRequest, client.send(path, "alice", "k", """{"toMemberId":"b o b"}""").status)
        assertEquals(HttpStatusCode.Forbidden, client.send(path, "agent", "k", body).status)
        assertEquals(HttpStatusCode.Forbidden, client.send(path, "reader", "k", body).status)
        assertEquals(HttpStatusCode.NotFound, client.send(path, "stranger", "k", body).status)
        val unknown = client.send("/api/v1/cloud/sessions/nope/transfer", "alice", "k", body)
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        val tooBig = """{"toMemberId":"bob","pad":"${"x".repeat(2000)}"}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, client.send(path, "alice", "k", tooBig).status)
    }

    @Test
    fun anOfferIsAcceptedThenReplayedWithItsKeyAndAnotherRequestUnderTheKeyIsRefused() = run {
        val target = running()
        val path = "/api/v1/cloud/sessions/${target.id}/transfer"

        val first = client.send(path, "alice", "key-1", """{"toMemberId":"bob","resume":true}""")
        assertEquals(HttpStatusCode.Accepted, first.status)
        assertEquals(null, first.headers["Idempotency-Replayed"])
        val replay = client.send(path, "alice", "key-1", """{"toMemberId":"bob","resume":true}""")
        assertEquals(HttpStatusCode.Accepted, replay.status)
        assertEquals("true", replay.headers["Idempotency-Replayed"])
        assertEquals(idOf(first.bodyAsText()), idOf(replay.bodyAsText()))
        val other = client.send(path, "alice", "key-1", """{"toMemberId":"carol"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, other.status)
    }

    @Test
    fun theRecipientAcceptsWithOrWithoutABodyAndDeclinesAndTheOwnerCancels() = run {
        val offered = client.send(
            "/api/v1/cloud/sessions/${running().id}/transfer", "alice", "k-1", """{"toMemberId":"bob"}""",
        )
        val id = idOf(offered.bodyAsText())

        assertEquals(HttpStatusCode.Forbidden, client.send("/api/v1/cloud/transfers/$id/accept", "agent").status)
        assertEquals(HttpStatusCode.NotFound, client.send("/api/v1/cloud/transfers/nope/accept", "bob").status)
        val accepted = client.send("/api/v1/cloud/transfers/$id/accept", "bob", body = """{"resume":true}""")
        assertEquals(HttpStatusCode.Accepted, accepted.status)
        assertTrue("\"state\":\"accepted\"" in accepted.bodyAsText(), accepted.bodyAsText())

        val bare = client.send(
            "/api/v1/cloud/sessions/${running().id}/transfer", "alice", "k-2", """{"toMemberId":"bob"}""",
        )
        val bareId = idOf(bare.bodyAsText())
        val text = client.send(
            "/api/v1/cloud/transfers/$bareId/accept", "bob", body = "x", contentType = ContentType.Text.Plain,
        )
        assertEquals(HttpStatusCode.Accepted, text.status)

        val declined = client.send(
            "/api/v1/cloud/sessions/${running().id}/transfer", "alice", "k-3", """{"toMemberId":"bob"}""",
        )
        val declinedId = idOf(declined.bodyAsText())
        val byAgent = client.send("/api/v1/cloud/transfers/$declinedId/decline", "agent")
        assertEquals(HttpStatusCode.Forbidden, byAgent.status)
        val decline = client.send("/api/v1/cloud/transfers/$declinedId/decline", "bob")
        assertEquals(HttpStatusCode.OK, decline.status)
        assertTrue("\"state\":\"declined\"" in decline.bodyAsText())

        val withdrawn = client.send(
            "/api/v1/cloud/sessions/${running().id}/transfer", "alice", "k-4", """{"toMemberId":"bob"}""",
        )
        val withdrawnId = idOf(withdrawn.bodyAsText())
        val cancelByAgent = client.send("/api/v1/cloud/transfers/$withdrawnId/cancel", "agent")
        assertEquals(HttpStatusCode.Forbidden, cancelByAgent.status)
        val cancel = client.send("/api/v1/cloud/transfers/$withdrawnId/cancel", "alice")
        assertEquals(HttpStatusCode.OK, cancel.status)
        assertTrue("\"state\":\"cancelled\"" in cancel.bodyAsText())
        val late = client.send("/api/v1/cloud/transfers/$withdrawnId/decline", "bob")
        assertEquals(HttpStatusCode.Conflict, late.status)
    }

    @Test
    fun theMembersListTheirTransfersWithFiltersAndACursorAndReadOneOfThem() = run {
        val one = client.send(
            "/api/v1/cloud/sessions/${running().id}/transfer", "alice", "k-1", """{"toMemberId":"bob"}""",
        )
        val two = client.send(
            "/api/v1/cloud/sessions/${running().id}/transfer", "alice", "k-2", """{"toMemberId":"bob"}""",
        )
        val oneId = idOf(one.bodyAsText())
        val twoId = idOf(two.bodyAsText())

        val all = client.read("/api/v1/cloud/transfers", "bob")
        assertEquals(HttpStatusCode.OK, all.status)
        assertTrue(oneId in all.bodyAsText() && twoId in all.bodyAsText())
        val page = client.read("/api/v1/cloud/transfers?limit=1&direction=incoming&state=offered", "bob")
        assertEquals(HttpStatusCode.OK, page.status)
        val last = idOf(page.bodyAsText())
        assertTrue(last in page.bodyAsText())
        val next = client.read("/api/v1/cloud/transfers?limit=1&direction=incoming&before=$last", "bob")
        assertEquals(HttpStatusCode.OK, next.status)
        assertEquals(HttpStatusCode.OK, client.read("/api/v1/cloud/transfers?direction=outgoing", "alice").status)
        assertEquals(HttpStatusCode.OK, client.read("/api/v1/cloud/transfers?direction=all", "alice").status)
        val bySession = "/api/v1/cloud/transfers?session=${Uuid.random()}"
        assertEquals(HttpStatusCode.OK, client.read(bySession, "alice").status)

        // A real cursor past the last page is an empty page; one that names no transfer is refused.
        val past = client.read("/api/v1/cloud/transfers?direction=outgoing&before=$twoId", "bob")
        assertEquals(HttpStatusCode.OK, past.status)
        val forged = "before=${Uuid.random()}"
        for (bad in listOf("direction=sideways", "state=nope", "session=x", "before=x", "limit=0", forged)) {
            assertEquals(HttpStatusCode.BadRequest, client.read("/api/v1/cloud/transfers?$bad", "alice").status, bad)
        }

        assertEquals(HttpStatusCode.OK, client.read("/api/v1/cloud/transfers/$oneId", "bob").status)
        assertEquals(HttpStatusCode.NotFound, client.read("/api/v1/cloud/transfers/$oneId", "carol").status)
        assertEquals(HttpStatusCode.NotFound, client.read("/api/v1/cloud/transfers/nope", "alice").status)
    }

    @Test
    fun theRecipientsAreListedForAMemberWhenMembersMayOfferAndRefusedOtherwise() = run {
        val listed = client.read("/api/v1/cloud/transfer-recipients", "alice")
        assertEquals(HttpStatusCode.OK, listed.status)
        assertTrue("\"login\":\"bob\"" in listed.bodyAsText(), listed.bodyAsText())
        policy.membersOffer = false
        assertEquals(HttpStatusCode.Forbidden, client.read("/api/v1/cloud/transfer-recipients", "alice").status)
        assertEquals(HttpStatusCode.OK, client.read("/api/v1/cloud/transfer-recipients", "carol").status)
    }

    @Test
    fun aDecidedTransferShowsWhenWhoAndWhy() = run {
        val at = Instant.parse("2026-10-30T10:00:00Z")
        val done = CloudTransfer(
            id = Uuid.random(), orgId = ORG_A, sessionId = Uuid.random(), fromMemberId = aliceId, toMemberId = bobId,
            initiatedBy = aliceId, state = TransferState.FAILED, resume = false, version = 3, createdAt = at,
            updatedAt = at, expiresAt = at, decidedBy = bobId, decidedAt = at, completedAt = at, atEpoch = 4,
            failure = SessionFailure("transfer_timeout", "Too slow."),
        ).also { transfers.transfers[it.id] = it }

        // An administrator's transfer is accepted at once and never expires.
        val own = client.send(
            "/api/v1/cloud/sessions/${running(carolId).id}/transfer", "carol", "k-admin", """{"toMemberId":"bob"}""",
        )
        assertEquals(HttpStatusCode.Accepted, own.status)
        assertEquals(null, transfers.transfers.getValue(Uuid.parse(idOf(own.bodyAsText()))).expiresAt)

        val read = client.read("/api/v1/cloud/transfers/${done.id}", "alice")
        assertEquals(HttpStatusCode.OK, read.status)
        for (part in listOf("\"atEpoch\":4", "transfer_timeout", "\"completedAt\":\"2026-10-30T10:00:00Z\"")) {
            assertTrue(part in read.bodyAsText(), read.bodyAsText())
        }
    }

    /**
     * `runners.cloud.onbehalf.writes.v1`, as TeamCode's: the header attributes a transfer write to the member it names,
     * but opens no session or transfer of theirs; the caller must be the owner, the recipient or the former owner.
     */
    @Test
    fun theHeaderAttributesATransferWriteButOpensNoOtherMembersSessionOrTransfer() = run {
        val body = """{"toMemberId":"bob"}"""
        fun path(owner: String = aliceId) = "/api/v1/cloud/sessions/${running(owner).id}/transfer"
        val forbidden = client.sendFor("alice", path(), "bob", "k-0", body)
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertTrue(OnBehalfOf.FORBIDDEN in forbidden.bodyAsText())
        val ghost = client.sendFor("ghost", path(), "carol-cli", "k-0", body)
        assertEquals(HttpStatusCode.UnprocessableEntity, ghost.status)
        assertTrue(OnBehalfOf.INVALID_MEMBER in ghost.bodyAsText())
        // An agent is refused before the header is read: a transfer write is a person's.
        assertEquals(HttpStatusCode.Forbidden, client.sendFor("x y", path(), "agent", "k-0", body).status)
        // Alice's session is not carol's, whoever the header names (TeamCode's visible()).
        assertEquals(HttpStatusCode.NotFound, client.sendFor("alice", path(), "carol-cli", "k-1", body).status)

        // Carol's own session, given away with the header naming alice: her rights, accepted at once, journaled for
        // alice.
        val given = client.sendFor("alice", path(carolId), "carol-cli", "k-2", body)
        assertEquals(HttpStatusCode.Accepted, given.status, given.bodyAsText())
        val requested = transfers.transfers.getValue(Uuid.parse(idOf(given.bodyAsText())))
        assertEquals(carolId, requested.fromMemberId)
        assertEquals(carolId to carolId, requested.initiatedBy to requested.decidedBy)
        assertEquals(TransferState.ACCEPTED, requested.state)

        // Alice's offers to bob: carol decides none of them as bob or as alice.
        for ((write, member) in listOf("accept" to bobId, "decline" to bobId, "cancel" to aliceId)) {
            val offer = client.send(path(), "alice", "k-$write", body)
            val id = idOf(offer.bodyAsText())
            val decided = client.sendFor(member, "/api/v1/cloud/transfers/$id/$write", "carol-cli")
            assertEquals(HttpStatusCode.NotFound, decided.status, write)
            assertEquals(TransferState.OFFERED, transfers.transfers.getValue(Uuid.parse(id)).state)
        }

        // Alice's offers to carol: carol decides them as herself, journaled for the member the header names.
        for ((write, status) in listOf("accept" to HttpStatusCode.Accepted, "decline" to HttpStatusCode.OK)) {
            val offer = client.send(path(), "alice", "k-to-carol-$write", """{"toMemberId":"carol"}""")
            val id = idOf(offer.bodyAsText())
            val decided = client.sendFor("bob", "/api/v1/cloud/transfers/$id/$write", "carol-cli")
            assertEquals(status, decided.status, decided.bodyAsText())
            assertEquals(carolId, transfers.transfers.getValue(Uuid.parse(id)).decidedBy)
        }

        val byCarol = audit.filter { it.actor.subjectId == "carol" }
        assertEquals(
            listOf("cloud.transfer.requested", "cloud.transfer.accepted", "cloud.transfer.declined"),
            byCarol.map { it.event },
        )
        assertEquals(listOf(aliceId, bobId, bobId), byCarol.map { it.details["effective_member_id"] })
        assertTrue(byCarol.all { it.details["credential_id"] == "pat-carol" })
        assertTrue(audit.filter { it.actor.subjectId == "alice" }.all { it.details["effective_member_id"] == aliceId })
    }
}
