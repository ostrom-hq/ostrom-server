// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates

import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.AskedVia
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateMode
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.GateSubject
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.gates.domain.services.GateReceiptSigner
import run.ostrom.server.gates.domain.usecases.SessionApproversUseCase
import run.ostrom.server.gates.infrastructure.PostgresGatesRepository
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.receipts.domain.models.ReceiptReader
import run.ostrom.server.receipts.domain.services.ReceiptReaders
import run.ostrom.server.testing.serverTest
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The approvers of a session's gates, as the receipts read them (TeamCode #238, parity TC-NEW-675a530-2): a person a
 * gate of the session was sent to, whatever became of the request, in any case; never another organization's.
 */
class SessionApproversPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val epoch = Instant.fromEpochSeconds(1_790_000_000)

    private val overrides = module {
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
        // A credential no actor resolver knows, resolved by its organization alone: `phone`, a member of A.
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                    OrgPrincipal(orgA, Uuid.random(), OrgRole.MEMBER).takeIf { principal.subjectId == "phone" }
            }
        }
        // `rev` is a member of A, `root` an administrator of A; anyone else belongs to no organization.
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? =
                    when (principal.subjectId) {
                        "rev" -> GateActor.person("rev", orgA, OrgRole.MEMBER)
                        "root" -> GateActor.person("root", orgA, OrgRole.ADMIN)
                        else -> null
                    }
            }
        }
    }

    private fun gate(org: Uuid, n: Long, sessions: List<String>) = Gate(
        id = Uuid.fromLongs(n, 0),
        orgId = org,
        key = "push-main:acme/api:$n",
        subject = GateSubject("acme/api", actionClass = "push-main", command = "git push", scope = "repo:acme/api"),
        node = null,
        label = "Push to main",
        mode = GateMode.ENFORCED,
        status = GateStatus.APPROVED,
        askedBy = listOf("lyes"),
        askedVia = setOf(AskedVia.HOOK),
        sessionIds = sessions,
        shownDigest = "sha256:$n",
        evidence = emptyList(),
        createdAt = epoch,
    )

    private fun request(gate: Gate, approver: String, status: RequestStatus) = ApprovalRequest(
        id = Uuid.random(),
        gateId = gate.id,
        orgId = gate.orgId,
        approverId = approver,
        status = status,
        createdAt = epoch,
    )

    @Test
    fun theApproversOfASessionsGatesAreTheyAGateWasSentTo() = serverTest(listOf(GatesModule), overrides) { server ->
        val repository = PostgresGatesRepository(server.driver)
        val joined = gate(orgA, 1, listOf("s-1", "s-2"))
        val other = gate(orgA, 2, listOf("s-3"))
        val theirs = gate(orgB, 3, listOf("s-4"))
        repository.transaction {
            insertGate(joined)
            insertRequests(
                listOf(request(joined, "Rev", RequestStatus.APPROVED), request(joined, "ops", RequestStatus.CLOSED)),
            )
            insertGate(other)
            insertRequests(listOf(request(other, "ops", RequestStatus.PENDING)))
        }
        repository.transaction {
            insertGate(theirs)
            insertRequests(listOf(request(theirs, "rev", RequestStatus.PENDING)))
        }

        val approvers = server.koin.get<SessionApproversUseCase>()
        assertTrue(approvers.approves(orgA, "rev", "s-1"), "In any case")
        assertTrue(approvers.approves(orgA, "ops", "s-2"), "Whatever became of the request")
        assertFalse(approvers.approves(orgA, "rev", "s-3"), "Another gate's session")
        assertFalse(approvers.approves(orgA, "rev", "s-4"), "Another organization's")
        assertEquals(listOf("s-1", "s-2"), approvers(orgA, "REV").sorted())
        assertEquals(listOf("s-1", "s-2", "s-3"), approvers(orgA, "ops").sorted())
        assertEquals(listOf("s-4"), approvers(orgB, "rev"))
        assertEquals(emptyList(), approvers(orgA, "nobody"))

        val readers = server.koin.get<ReceiptReaders>()
        assertEquals(ReceiptReader(orgA, "rev", readsAll = false), readers.resolve(person("rev")))
        assertEquals(ReceiptReader(orgA, "root", readsAll = true), readers.resolve(person("root")))
        assertNull(readers.resolve(person("stranger")))
        assertEquals(ReceiptReader(orgA, null, readsAll = false), readers.resolve(person("phone")), "No login known")
        assertTrue(readers.approves(orgA, "rev", "s-2"))
        assertEquals(listOf("s-1", "s-2"), readers.approvedSessions(orgA, "rev").sorted())
    }

    private fun person(id: String) = AuthenticatedPrincipal(id, PrincipalKind.USER, setOf("user:*"))
}
