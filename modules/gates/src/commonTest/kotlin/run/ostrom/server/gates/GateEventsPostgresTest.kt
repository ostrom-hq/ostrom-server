// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.AskedVia
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateMode
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.GateSubject
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.domain.services.GateReceiptSigner
import run.ostrom.server.gates.infrastructure.PostgresGatesRepository
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The approval events of the gates, published in the gate's own transaction (the prerequisite of the durable
 * notifications, TC-NEW-a6a3bd8-2): one per request with its own status, ids and the approver's login only, confined
 * to the gate's organization.
 */
class GateEventsPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val epoch = Instant.fromEpochSeconds(1_790_000_000)

    // The gates' routes need a signer; the receipts module is not loaded here.
    private val overrides = module {
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
    }

    // What each publication was given, and the organization its transaction was confined to when it came.
    private class Recorder : OrgEventPublisher {
        val published = mutableListOf<Pair<String, List<OrgEventDraft>>>()

        override suspend fun publish(orgId: Uuid, events: List<OrgEventDraft>) = error("Only in the gate's transaction")

        override suspend fun publishIn(transaction: Transaction, orgId: Uuid, events: List<OrgEventDraft>) {
            val org = transaction.fetchAll(Statement.create("SELECT current_setting('ostrom.org_id') AS org"))
                .getOrThrow().first().get("org").asString()
            published += org to events
        }
    }

    private fun gate(repo: String?, status: GateStatus = GateStatus.PENDING) = Gate(
        id = Uuid.random(),
        orgId = orgA,
        key = "push-main:${Uuid.random()}",
        subject = GateSubject(repo, actionClass = "push-main", command = "git push", scope = "repo"),
        node = null,
        label = "Push to main",
        mode = GateMode.ENFORCED,
        status = status,
        askedBy = listOf("lyes"),
        askedVia = setOf(AskedVia.HOOK),
        sessionIds = emptyList(),
        shownDigest = "sha256:1",
        evidence = emptyList(),
        createdAt = epoch,
    )

    private fun request(gate: Gate, approver: String, status: RequestStatus = RequestStatus.PENDING) =
        ApprovalRequest(Uuid.random(), gate.id, gate.orgId, approver, status, createdAt = epoch)

    private fun data(vararg pairs: Pair<String, String>) =
        JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    @Test
    fun eachRequestIsInTheFeedInTheGatesTransaction() = serverTest(listOf(GatesModule), overrides) { server ->
        val recorder = Recorder()
        val repository = PostgresGatesRepository(server.driver, recorder)
        val asked = gate("acme/api")
        val requests = listOf(request(asked, "rev"), request(asked, "ops"))
        repository.transaction {
            insertGate(asked)
            insertRequests(requests)
            announce(OrgEventType.APPROVAL_REQUESTED, asked, requests)
        }
        val (org, events) = recorder.published.single()
        assertEquals(orgA.toString(), org, "Confined to the gate's organization")
        assertEquals(requests.map { it.id.toString() }, events.map { it.subjectId })
        assertEquals(listOf(null, null), events.map { it.memberId }, "The whole organization's: a login, not a member")
        assertEquals(
            data("approvalId" to requests[0].id.toString(), "gateId" to asked.id.toString(), "approver" to "rev",
                "repo" to "acme/api"),
            events[0].data,
            "Ids, the approver and the repository, never the label",
        )

        // Answered: each request with its own status, the closed one as closed.
        val decided = gate(null, GateStatus.REJECTED)
        val answered =
            listOf(request(decided, "ops", RequestStatus.REJECTED), request(decided, "rev", RequestStatus.CLOSED))
        repository.transaction {
            insertGate(decided)
            insertRequests(answered)
            announce(OrgEventType.APPROVAL_DECIDED, decided, answered)
        }
        val statuses = recorder.published.last().second.map { it.data.getValue("status") }
        assertEquals(listOf(JsonPrimitive("rejected"), JsonPrimitive("closed")), statuses)
        // A gate without a repository is asked with ids and the approver alone.
        val bare = gate(null)
        repository.transaction { announce(OrgEventType.APPROVAL_REQUESTED, bare, listOf(request(bare, "rev"))) }
        assertEquals(setOf("approvalId", "gateId", "approver"), recorder.published.last().second.single().data.keys)
    }

    @Test
    fun withoutRequestsNothingIsPublished() = serverTest(listOf(GatesModule), overrides) { server ->
        val recorder = Recorder()
        val asked = gate("acme/api")
        PostgresGatesRepository(server.driver, recorder).transaction {
            announce(OrgEventType.APPROVAL_REQUESTED, asked, emptyList())
        }
        assertEquals(emptyList(), recorder.published)
        // The default feed discards: a gates-only server still opens gates.
        PostgresGatesRepository(server.driver).transaction {
            announce(OrgEventType.APPROVAL_REQUESTED, asked, listOf(request(asked, "rev")))
        }
    }
}
