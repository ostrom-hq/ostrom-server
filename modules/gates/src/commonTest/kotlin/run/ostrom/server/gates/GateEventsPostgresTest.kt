// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.AskedVia
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateMode
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.GateSubject
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.infrastructure.PostgresGatesRepository
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The approval events of the gates, published in the gate's own transaction (the prerequisite of the durable
 * notifications, TC-NEW-a6a3bd8-2): one per request whose approver is a member, ids only, confined to the gate's
 * organization.
 */
class GateEventsPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val rev = Uuid.parse("00000000-0000-0000-0000-0000000000e1")
    private val ops = Uuid.parse("00000000-0000-0000-0000-0000000000e2")
    private val epoch = Instant.fromEpochSeconds(1_790_000_000)

    // `Rev` and `ops` are members of A, in any case; `gone` left.
    private val logins = object : MemberLogins {
        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
            logins.mapNotNull { login ->
                mapOf("rev" to rev, "ops" to ops)[login.lowercase()]?.let { login to it }
            }.toMap()

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> = emptyMap()
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

    @Test
    fun eachMemberApproverIsToldInTheGatesTransaction() = serverTest(listOf(GatesModule)) { server ->
        val recorder = Recorder()
        val repository = PostgresGatesRepository(server.driver, recorder, logins)
        val asked = gate("acme/api")
        val requests = listOf(request(asked, "Rev"), request(asked, "gone"), request(asked, "ops"))
        repository.transaction {
            insertGate(asked)
            insertRequests(requests)
            announce(OrgEventType.APPROVAL_REQUESTED, asked, requests)
        }
        val (org, events) = recorder.published.single()
        assertEquals(orgA.toString(), org, "Confined to the gate's organization")
        assertEquals(listOf(requests[0].id.toString(), requests[2].id.toString()), events.map { it.subjectId })
        assertEquals(listOf(rev, ops), events.map { it.memberId }, "The approver who left is told nothing")
        assertEquals(
            JsonObject(
                mapOf(
                    "approvalId" to JsonPrimitive(requests[0].id.toString()),
                    "gateId" to JsonPrimitive(asked.id.toString()),
                    "repo" to JsonPrimitive("acme/api"),
                ),
            ),
            events[0].data,
            "Ids and the repository, never the label",
        )

        val decided = gate(null, GateStatus.REJECTED)
        val answered = listOf(request(decided, "ops", RequestStatus.REJECTED))
        repository.transaction {
            insertGate(decided)
            insertRequests(answered)
            announce(OrgEventType.APPROVAL_DECIDED, decided, answered)
        }
        assertEquals(
            JsonObject(
                mapOf(
                    "approvalId" to JsonPrimitive(answered[0].id.toString()),
                    "gateId" to JsonPrimitive(decided.id.toString()),
                    "status" to JsonPrimitive("rejected"),
                ),
            ),
            recorder.published.last().second.single().data,
        )
        // A gate without a repository is asked with ids alone.
        val bare = gate(null)
        repository.transaction { announce(OrgEventType.APPROVAL_REQUESTED, bare, listOf(request(bare, "rev"))) }
        assertEquals(setOf("approvalId", "gateId"), recorder.published.last().second.single().data.keys)
    }

    @Test
    fun withoutRequestsOrMembersNothingIsPublished() = serverTest(listOf(GatesModule)) { server ->
        val recorder = Recorder()
        val asked = gate("acme/api")
        PostgresGatesRepository(server.driver, recorder, logins).transaction {
            announce(OrgEventType.APPROVAL_REQUESTED, asked, emptyList())
        }
        // The orgs module not loaded: no login names a member.
        PostgresGatesRepository(server.driver, recorder).transaction {
            announce(OrgEventType.APPROVAL_REQUESTED, asked, listOf(request(asked, "rev")))
        }
        assertEquals(emptyList(), recorder.published)
        // The default feed discards: a gates-only server still opens gates.
        PostgresGatesRepository(server.driver, logins = logins).transaction {
            announce(OrgEventType.APPROVAL_REQUESTED, asked, listOf(request(asked, "rev")))
        }
    }
}
