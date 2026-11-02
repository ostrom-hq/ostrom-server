// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.gates.infrastructure.PostgresGatesRepository
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.testing.assertWithinBudget
import dev.ostrom.server.testing.measureRead
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.testing.writeReadReport
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `GET /api/v1/approvals/queue` on 10 organizations of 2 000 gates each, two approval requests per gate: the queue of
 * the approver of every gate (100 pending, the page's maximum) and of one of 20 reviewers. Each keeps the read budget
 * (ReadBench.kt); its queries, plans and p95 are written to `build/reports/perf/gates.txt`.
 */
class GatesReadsBenchTest {
    private val orgs = List(ORGS) { Uuid.fromLongs(0, it.toLong() + 1) }
    private val acme = orgs.first()
    private val epoch = Instant.fromEpochSeconds(1_790_000_000)

    // The token is the person: a browser session of acme.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = AuthenticatedPrincipal(
                    token,
                    PrincipalKind.USER,
                    setOf(GateScopes.ALL_USER),
                    channel = PrincipalChannel.BROWSER,
                    authenticatedAt = Clock.System.now(),
                )
            }
        }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor =
                    GateActor(principal.subjectId, acme, ActorKind.USER, personId = principal.subjectId)
            }
        }
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
    }

    @Test
    fun theApprovalQueueKeepsItsBudget() = serverTest(listOf(GatesModule), overrides) { server ->
        val repository = PostgresGatesRepository(server.driver)
        orgs.forEachIndexed { index, org ->
            val gates = (0 until GATES_PER_ORG).map { gateOf(org, index * GATES_PER_ORG + it) }
            repository.transaction {
                for (gate in gates) {
                    insertGate(gate)
                    insertRequests(listOf(requestOf(gate, 0), requestOf(gate, 1)))
                }
            }
        }
        for (table in listOf("gates_gates", "gates_requests", "gates_sessions")) {
            server.database.superuser.execute("VACUUM ANALYZE $table").getOrThrow()
        }

        val measures = listOf(
            "GET /api/v1/approvals/queue (approver of every gate)" to OPS,
            "GET /api/v1/approvals/queue (one reviewer of 20)" to "rev-5@acme.test",
        ).map { (name, person) -> server.measureRead(name) { get("/api/v1/approvals/queue") { bearerAuth(person) } } }
        writeReadReport("gates", measures)
        measures.forEach { it.assertWithinBudget(setOf("gates_gates", "gates_requests")) }
    }

    private fun gateOf(org: Uuid, n: Int) = Gate(
        id = Uuid.fromLongs(n.toLong() + 1, 0),
        orgId = org,
        key = "push-main:acme/api:${n % KEYS}",
        subject = GateSubject("acme/api", actionClass = "push-main", command = "git push", scope = "repo:acme/api"),
        node = null,
        label = "Push to main",
        mode = GateMode.ENFORCED,
        status = if (n % 10 == 0) GateStatus.PENDING else GateStatus.APPROVED,
        askedBy = listOf("dev-a"),
        askedVia = setOf(AskedVia.HOOK),
        sessionIds = listOf("claude-${n % SESSIONS}"),
        shownDigest = "sha256:$n",
        evidence = emptyList(),
        createdAt = epoch - n.minutes,
    )

    // A request id is its gate's, with the approver's position in the low half; the first approver is one of 20.
    private fun requestOf(gate: Gate, position: Int) = ApprovalRequest(
        id = gate.id.toLongs { high, _ -> Uuid.fromLongs(high, position.toLong() + 1) },
        gateId = gate.id,
        orgId = gate.orgId,
        approverId = if (position == 0) "rev-${gate.id.toLongs { high, _ -> high } % APPROVERS}@acme.test" else OPS,
        status = if (gate.status == GateStatus.PENDING) RequestStatus.PENDING else RequestStatus.APPROVED,
        createdAt = gate.createdAt,
    )

    private companion object {
        const val ORGS = 10
        const val GATES_PER_ORG = 2_000
        const val KEYS = 300
        const val SESSIONS = 1_000
        const val APPROVERS = 20
        const val OPS = "ops@acme.test"
    }
}
