// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.fakes

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.ApprovalView
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.gates.domain.repositories.GatesTransaction
import dev.ostrom.server.gates.domain.services.GateDigester
import dev.ostrom.server.gates.domain.services.GateNotifier
import dev.ostrom.server.gates.domain.services.GatePolicies
import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Gates in memory; a transaction holds one mutex (like the row lock) and rolls back on an exception. */
class InMemoryGatesRepository : GatesRepository {
    val gates = linkedMapOf<Uuid, Gate>()
    val requests = linkedMapOf<Uuid, ApprovalRequest>()
    val lockedKeys = mutableListOf<String>()
    var gateUpdates = 0
    var transactions = 0

    /** When set, `byKey` returns this snapshot in place of the stored gate: a row read before another commit. */
    var staleSnapshot: Gate? = null

    /** When set, `lockGate` of this gate returns null once (the row vanished between two transactions). */
    var vanishOnLock: Uuid? = null
    private val mutex = Mutex()

    override suspend fun <T> transaction(block: suspend GatesTransaction.() -> T): T = mutex.withLock {
        transactions++
        val savedGates = LinkedHashMap(gates)
        val savedRequests = LinkedHashMap(requests)
        try {
            Tx().block()
        } catch (error: Throwable) {
            gates.clear(); gates.putAll(savedGates)
            requests.clear(); requests.putAll(savedRequests)
            throw error
        }
    }

    override suspend fun get(orgId: Uuid, id: Uuid): Gate? = gates[id]?.takeIf { it.orgId == orgId }

    override suspend fun requestsOf(orgId: Uuid, gateId: Uuid): List<ApprovalRequest> =
        requests.values.filter { it.gateId == gateId && it.orgId == orgId }

    override suspend fun approval(orgId: Uuid, id: Uuid): ApprovalView? =
        requests[id]?.takeIf { it.orgId == orgId }
            ?.let { request -> gates[request.gateId]?.let { ApprovalView(request, it) } }

    override suspend fun approvals(
        orgId: Uuid,
        approverId: String?,
        status: RequestStatus?,
        limit: Int,
    ): List<ApprovalView> =
        requests.values.filter {
            it.orgId == orgId &&
                (approverId == null || it.approverId.equals(approverId, ignoreCase = true)) &&
                (status == null || it.status == status)
        }.reversed().take(limit).mapNotNull { request -> gates[request.gateId]?.let { ApprovalView(request, it) } }

    private inner class Tx : GatesTransaction {
        override suspend fun lockKey(orgId: Uuid, key: String) {
            lockedKeys += key
        }

        override suspend fun byKey(orgId: Uuid, key: String, limit: Int): List<Gate> =
            gates.values.filter { it.orgId == orgId && it.key == key }.reversed().take(limit)
                .map { gate -> staleSnapshot?.takeIf { it.id == gate.id } ?: gate }

        override suspend fun addAskers(gate: Gate) {
            gateUpdates++
            gates[gate.id] = gates.getValue(gate.id).copy(
                askedBy = gate.askedBy,
                askedVia = gate.askedVia,
                sessionIds = gate.sessionIds,
                launchIds = gate.launchIds,
            )
        }

        override suspend fun lockGate(orgId: Uuid, id: Uuid): Gate? {
            if (vanishOnLock == id) {
                vanishOnLock = null
                return null
            }
            return get(orgId, id)
        }

        override suspend fun request(orgId: Uuid, id: Uuid): ApprovalRequest? =
            requests[id]?.takeIf { it.orgId == orgId }

        override suspend fun requestsOf(orgId: Uuid, gateId: Uuid): List<ApprovalRequest> =
            this@InMemoryGatesRepository.requestsOf(orgId, gateId)

        override suspend fun insertGate(gate: Gate) {
            gates[gate.id] = gate
        }

        override suspend fun updateGate(gate: Gate) {
            gateUpdates++
            gates[gate.id] = gate
        }

        override suspend fun insertRequests(requests: List<ApprovalRequest>) {
            requests.forEach { this@InMemoryGatesRepository.requests[it.id] = it }
        }

        override suspend fun updateRequests(requests: List<ApprovalRequest>) {
            requests.forEach { this@InMemoryGatesRepository.requests[it.id] = it }
        }
    }
}

/** The preimage itself stands for its digest: equal texts, equal digests. */
class IdentityDigester : GateDigester {
    override fun digest(text: String): String = text
}

class FakePolicies(var policy: SelfApprovalPolicy = SelfApprovalPolicy.SOLE_APPROVER) : GatePolicies {
    override suspend fun selfApproval(orgId: Uuid): SelfApprovalPolicy = policy
}

class FakeSessions : GateSessions {
    val closed = mutableSetOf<String>()

    // A session belongs to the launch `launch-<session>`, the way [agent] scopes its token.
    override suspend fun assertOpen(orgId: Uuid, sessionId: String, launchId: String?) {
        if (sessionId in closed) throw OstromException(ErrorKind.CONFLICT, "session closed")
        if (launchId != null && launchId != "launch-$sessionId") {
            throw OstromException(ErrorKind.FORBIDDEN, "other launch")
        }
    }
}

class FakeSigner : GateReceiptSigner {
    val signed = mutableListOf<Gate>()
    var failure: Throwable? = null

    override suspend fun sign(gate: Gate): String {
        failure?.let { throw it }
        signed += gate
        return "rcpt_${gate.id}"
    }
}

class FakeNotifier : GateNotifier {
    val requested = mutableListOf<Pair<Gate, List<ApprovalRequest>>>()
    val decided = mutableListOf<Pair<Gate, List<ApprovalRequest>>>()
    var failure: Throwable? = null

    override suspend fun requested(gate: Gate, requests: List<ApprovalRequest>) {
        failure?.let { throw it }
        requested += gate to requests
    }

    override suspend fun decided(gate: Gate, requests: List<ApprovalRequest>) {
        failure?.let { throw it }
        decided += gate to requests
    }
}

class RecordingLogger : Logger {
    val lines = mutableListOf<Pair<LogLevel, String>>()

    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        lines += level to message
    }

    override fun with(fields: Map<String, Any?>): Logger = this
}

class SettableClock(var now: Instant = Instant.parse("2026-10-30T02:00:00Z")) : Clock {
    override fun now(): Instant = now
}

val ORG: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000a")
val OTHER_ORG: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000b")
const val SESSION = "s-0000051"
const val OTHER_SESSION = "s-0000052"

// Every credential acts for a person (S2-15): a user and a device for their own, an agent session for `lyes`.
fun user(id: String, org: Uuid = ORG) = GateActor(id, org, ActorKind.USER, personId = id)
fun agent(session: String = SESSION, org: Uuid = ORG) =
    GateActor("agent-$session", org, ActorKind.AGENT_SESSION, launchId = "launch-$session", personId = "lyes")
fun device(id: String = "lyes") = GateActor("device-$id", ORG, ActorKind.DEVICE, personId = id)
fun ciService() = GateActor("github-app", ORG, ActorKind.SERVICE)

const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
const val SHA_B = "0123456789abcdef0123456789abcdef01234567"
