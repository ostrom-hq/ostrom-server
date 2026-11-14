// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.AskedVia
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateMode
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.GateSubject
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.domain.repositories.GatesRepository
import run.ostrom.server.gates.fakes.InMemoryGatesRepository
import run.ostrom.server.gates.fakes.ORG
import run.ostrom.server.gates.fakes.OTHER_ORG
import run.ostrom.server.gates.infrastructure.GateSummariesImpl
import run.ostrom.server.kernel.events.GateSummaries
import run.ostrom.server.kernel.events.GateSummary
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What the notification router reads of the gates its approval events name. */
class GateSummariesTest {
    private val epoch = Instant.fromEpochSeconds(1_790_000_000)
    private val repository = InMemoryGatesRepository()

    private fun gate(status: GateStatus, org: Uuid = ORG, repo: String? = "acme/app") = Gate(
        id = Uuid.random(),
        orgId = org,
        key = "k-${Uuid.random()}",
        subject = GateSubject(repo, actionClass = "push-main", command = "git push", scope = "repo"),
        node = null,
        label = "Push to main",
        mode = GateMode.ENFORCED,
        status = status,
        askedBy = listOf("ana"),
        askedVia = setOf(AskedVia.HOOK),
        sessionIds = emptyList(),
        shownDigest = "sha256:1",
        evidence = emptyList(),
        createdAt = epoch,
    ).also { repository.gates[it.id] = it }

    private fun request(gate: Gate, approver: String, status: RequestStatus = RequestStatus.PENDING) =
        ApprovalRequest(Uuid.random(), gate.id, gate.orgId, approver, status, createdAt = epoch)
            .also { repository.requests[it.id] = it }

    @Test
    fun aGateIsOpenWhilePendingAndNamesTheApproversStillAsked() {
        runTest {
            val pending = gate(GateStatus.PENDING)
            request(pending, "bob")
            request(pending, "cara", RequestStatus.CLOSED)
            request(pending, "dan")
            val approved = gate(GateStatus.APPROVED, repo = null)
            request(approved, "bob", RequestStatus.APPROVED)
            val elsewhere = gate(GateStatus.PENDING, org = OTHER_ORG)

            val summaries = GateSummariesImpl(repository).of(
                ORG,
                setOf(pending.id.toString(), approved.id.toString(), elsewhere.id.toString(), "not-a-gate"),
            )

            assertEquals(
                setOf(
                    GateSummary(
                        pending.id.toString(), true, listOf("ana"), "Push to main", "acme/app", listOf("bob", "dan"),
                    ),
                    GateSummary(approved.id.toString(), false, listOf("ana"), "Push to main", null, emptyList()),
                ),
                summaries.toSet(),
                "Another organization's gate and an id of nobody's are absent",
            )
            assertEquals(1, repository.batchedRequestReads, "The requests of every gate in one read")
        }
    }

    @Test
    fun noGateFoundReadsNoRequest() {
        runTest {
            assertEquals(emptyList(), GateSummariesImpl(repository).of(ORG, setOf(Uuid.random().toString())))
            assertEquals(0, repository.batchedRequestReads)
        }
    }

    @Test
    fun theModuleBindsTheSummariesOnItsGates() {
        val app = koinApplication {
            allowOverride(true)
            modules(GatesModule.koinModule, module { single<GatesRepository> { repository } })
        }
        assertIs<GateSummariesImpl>(app.koin.get<GateSummaries>())
        app.close()
    }
}
