// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.fakes

import dev.ostrom.core.telemetry.metrics.MeterImpl
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.usecases.ListSessionGatesUseCase
import dev.ostrom.server.gates.domain.usecases.SessionGate
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.kernel.observability.OstromMetrics
import dev.ostrom.server.receipts.domain.services.ReceiptRule
import dev.ostrom.server.receipts.domain.services.ReceiptRules
import dev.ostrom.server.receipts.domain.usecases.GetReceiptUseCase
import dev.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.models.SessionQuery
import dev.ostrom.server.sessions.domain.repositories.SessionsRepository
import dev.ostrom.server.sessions.domain.repositories.SessionsTransaction
import dev.ostrom.server.sessions.domain.repositories.StaleSession
import dev.ostrom.server.sessions.domain.services.SessionCosts
import dev.ostrom.server.sessions.domain.usecases.CloseSessionUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.EndSessionUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.GetSessionUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.ListSessionsUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.ReopenSessionUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.ReportSeenUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.SessionAliveUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.SessionSegments
import dev.ostrom.server.sessions.domain.usecases.StartSessionUseCaseImpl
import dev.ostrom.server.sessions.domain.usecases.SweepStaleSessionsUseCaseImpl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

val ORG: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000a")
val OTHER_ORG: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000000b")

/** An agent token of `lyes`, scoped to `launch`. */
fun agent(launch: String = "launch-1", person: String = "lyes", org: Uuid = ORG) =
    GateActor("agent-$launch", org, ActorKind.AGENT_SESSION, launchId = launch, personId = person)

/** A person's own credential. */
fun user(id: String = "lyes", org: Uuid = ORG) = GateActor(id, org, ActorKind.USER, personId = id)

class SettableClock(var now: Instant = Instant.parse("2026-10-30T09:00:00.123456Z")) : Clock {
    override fun now(): Instant = now
}

class InMemorySessions : SessionsRepository {
    val rows = linkedMapOf<Pair<Uuid, String>, Session>()
    val locks = mutableListOf<String>()
    var staleCutoff: Instant? = null

    /** The reads of a segment outside a transaction, each one a round trip of its own. */
    var gets = 0

    override suspend fun <T> transaction(orgId: Uuid, block: suspend SessionsTransaction.() -> T): T = Tx(orgId).block()

    override suspend fun get(orgId: Uuid, id: String): Session? {
        gets++
        return rows[orgId to id]
    }

    override suspend fun segmentsOf(orgId: Uuid, id: String): List<Session> {
        val root = rows[orgId to id]?.rootId
        return rows.values.filter { it.orgId == orgId && it.rootId == root }.sortedBy { it.segment }
    }

    override suspend fun list(orgId: Uuid, query: SessionQuery): List<Session> =
        rows.values.filter { it.orgId == orgId && (query.launchId == null || it.launchId == query.launchId) }
            .sortedByDescending { it.startedAt }
            .take(query.limit)

    override suspend fun stale(seenBefore: Instant, limit: Int): List<StaleSession> {
        staleCutoff = seenBefore
        return rows.values.filter { it.endedAt == null && it.lastSeenAt?.let { seen -> seen < seenBefore } == true }
            .map { StaleSession(it.orgId, it.id, it.lastSeenAt ?: error("filtered")) }
            .take(limit)
    }

    private inner class Tx(private val orgId: Uuid) : SessionsTransaction {
        override suspend fun lockRoot(rootId: String) {
            locks += rootId
        }

        override suspend fun segments(rootId: String): List<Session> =
            rows.values.filter { it.orgId == orgId && it.rootId == rootId }.sortedBy { it.segment }

        override suspend fun lock(id: String): Session? = rows[orgId to id]

        override suspend fun insert(session: Session) {
            check((orgId to session.id) !in rows) { "duplicate" }
            rows[orgId to session.id] = session
        }

        override suspend fun update(session: Session) {
            rows[orgId to session.id] = session
        }
    }
}

class FakeSessionGates : ListSessionGatesUseCase {
    val gates = mutableMapOf<String, List<SessionGate>>()

    /** The sessions the end closed on the gates' side, with the time it gave, in order. */
    val closed = mutableListOf<Pair<String, Instant>>()

    /** The lists of a session's gates outside its end. */
    var listed = 0

    override suspend fun invoke(orgId: Uuid, sessionId: String): List<SessionGate> {
        listed++
        return gates[sessionId].orEmpty()
    }

    override suspend fun close(orgId: Uuid, sessionId: String, endedAt: Instant): List<SessionGate> {
        closed += sessionId to endedAt
        return gates[sessionId].orEmpty()
    }
}

class FakeRules : ReceiptRules {
    private val rules =
        listOf(ReceiptRule("r-commit-style", "79b6ac48e5225be9037493c1e8a8fc9e5fbc42e2893b0c15b8846bd39d310cd7"))

    /** Runs once, the first time the rules are read: what happens while an end reads before its lock. */
    var meanwhile: (suspend () -> Unit)? = null

    override suspend fun rulesFor(orgId: Uuid, repo: String?): List<ReceiptRule> {
        meanwhile?.also { meanwhile = null }?.invoke()
        return rules
    }
}

/** Signs by adding the id it is given; remembers every draft. */
/** What the server measured a session cost; none until a test says. */
class FakeCosts : SessionCosts {
    var cost: Double? = null

    /** The launch and the window each close asked about. */
    val asked = mutableListOf<Triple<String?, Instant, Instant>>()

    /** Runs once, the first time a cost is asked: what happens while a close reads before its lock. */
    var meanwhile: (suspend () -> Unit)? = null

    override suspend fun costOf(orgId: Uuid, launchId: String?, from: Instant, to: Instant): Double? {
        asked += Triple(launchId, from, to)
        meanwhile?.also { meanwhile = null }?.invoke()
        return cost
    }
}

class FakeSign :
    SignReceiptUseCase,
    GetReceiptUseCase {
    val signed = linkedMapOf<String, JsonObject>()

    /** How many of the next signatures fail, as a database that went away would. */
    var failing = 0

    override suspend fun invoke(orgId: Uuid, draft: JsonObject, id: String?): JsonObject {
        if (failing > 0) error("signing failed (${failing--} left)")
        val receiptId = id ?: error("sessions always give the id")
        return signed.getOrPut(receiptId) { JsonObject(draft + ("id" to JsonPrimitive(receiptId))) }
    }

    /** The reads of a stored receipt. */
    var reads = 0

    override suspend fun invoke(orgId: Uuid, id: String): JsonObject {
        reads++
        return signed.getValue(id)
    }
}

class RecordingLogger : Logger {
    val lines = mutableListOf<Pair<String, Throwable?>>()

    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        lines += message to error
    }

    override fun with(fields: Map<String, Any?>): Logger = this
}

/** The sessions use cases on fakes. */
class SessionBench {
    val repository = InMemorySessions()
    val gates = FakeSessionGates()
    val rules = FakeRules()
    val sign = FakeSign()
    val clock = SettableClock()
    val logger = RecordingLogger()
    val costs = FakeCosts()
    val close = CloseSessionUseCaseImpl(repository, gates, rules, sign, costs, clock)
    val meter = MeterImpl(Clock.System)
    val segments = SessionSegments(close, clock, OstromMetrics(meter))
    val start = StartSessionUseCaseImpl(repository, segments)
    val get = GetSessionUseCaseImpl(repository, gates, sign)
    val end = EndSessionUseCaseImpl(repository, close, get)
    val alive = SessionAliveUseCaseImpl(repository, clock)
    val reopen = ReopenSessionUseCaseImpl(repository, segments)
    val seen = ReportSeenUseCaseImpl(repository, clock)
    val list = ListSessionsUseCaseImpl(repository)
    val sweep = SweepStaleSessionsUseCaseImpl(repository, close, clock, logger)
}
