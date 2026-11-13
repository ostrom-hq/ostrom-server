// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations.fakes

import run.ostrom.server.automations.domain.CreateOutcome
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAgent
import run.ostrom.server.automations.domain.WaitAgentResolver
import run.ostrom.server.automations.domain.WaitAnswer
import run.ostrom.server.automations.domain.WaitCaller
import run.ostrom.server.automations.domain.WaitEvents
import run.ostrom.server.automations.domain.WaitFilter
import run.ostrom.server.automations.domain.WaitSessions
import run.ostrom.server.automations.domain.WaitSessionsImpl
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.domain.WaitsRepository
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.sessions.AgentSession
import run.ostrom.server.kernel.sessions.AgentSessions
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

val ORG_A: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
val ORG_B: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000bb")
val ALICE: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000a11c")
val BOB: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000b0b")
val DAVE: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000da7e")
val CAROL: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000ca01")

val alice = WaitCaller(ORG_A, ALICE.toString(), admin = false, interactive = true)
val bob = WaitCaller(ORG_A, BOB.toString(), admin = false, interactive = true)
val dave = WaitCaller(ORG_A, DAVE.toString(), admin = true, interactive = true)
val daveKey = WaitCaller(ORG_A, DAVE.toString(), admin = true, interactive = false)
val carol = WaitCaller(ORG_B, CAROL.toString(), admin = true, interactive = true)

/** The members of the tests: alice, bob and dave in org A, carol in org B. */
object TestMembers : MemberDirectory {
    override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = when (orgId) {
        ORG_A -> memberId in setOf(ALICE, BOB, DAVE).map { it.toString() }
        else -> memberId == CAROL.toString()
    }
}

/** The usernames of the tests' members. */
object TestLogins : MemberLogins {
    private val names = mapOf(
        ORG_A to mapOf(ALICE to "alice", BOB to "bob", DAVE to "dave"),
        ORG_B to mapOf(CAROL to "carol"),
    )

    override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> {
        val byLogin = names[orgId].orEmpty().entries.associate { (id, login) -> login to id }
        return logins.mapNotNull { login -> byLogin[login.lowercase()]?.let { login to it } }.toMap()
    }

    override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
        names[orgId].orEmpty().filterKeys { it in memberIds }
}

/** A session of org A that ended. */
const val ENDED_SESSION = "ended-1"

/** A session org A does not have. */
const val UNKNOWN_SESSION = "unknown-1"

/**
 * The agent sessions of the tests, in org A: `alice-…` are alice's, [UNKNOWN_SESSION] is none, every other is bob's,
 * running but [ENDED_SESSION]. Org B has none.
 */
object TestSessions : AgentSessions {
    override suspend fun find(orgId: Uuid, sessionId: String): AgentSession? = when {
        orgId != ORG_A || sessionId == UNKNOWN_SESSION -> null
        sessionId.startsWith("alice-") -> AgentSession("alice", true, listOf(sessionId))
        else -> AgentSession("bob", sessionId != ENDED_SESSION, listOf(sessionId))
    }
}

/** The tests' [WaitSessions]. */
val testWaitSessions: WaitSessions = WaitSessionsImpl(TestSessions, TestLogins)

/** Records what it is told. */
class RecordingEvents : WaitEvents {
    val seen = mutableListOf<Pair<String, Uuid>>()

    override suspend fun created(wait: Wait) {
        seen += "created" to wait.id
    }

    override suspend fun resolved(wait: Wait) {
        seen += "resolved" to wait.id
    }

    override suspend fun cancelled(wait: Wait) {
        seen += "cancelled" to wait.id
    }
}

/** A browser session's scopes. */
private val allUserScopes = setOf("user:*")

/**
 * The test identities: `alice`, `bob` (members of org A), `dave` (admin of org A), `carol` (owner of org B) and
 * `nobody` (no org) sign in themselves; `reader`, `agent` and `writer` are bob's tokens with `waits:read`,
 * `waits:ask` and `waits:write`; `davekey` is dave's with `waits:write`; `cloud` is bob's with no waits scope;
 * `alicekey` is alice's personal token with `waits:write`, `aliceapp` alice in the unlocked mobile app;
 * `autoreader` and `autowriter` are alice's with `automations:read` and `automations:write`; `launch` is the agent
 * token of bob's launch `lch_1` and `ended` the agent token of a launch that is over (see [FakeWaitAgents]).
 */
val testIdentities = module {
    single<TokenAuthenticator> {
        object : TokenAuthenticator {
            override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
                "alice", "bob", "dave", "carol", "nobody" ->
                    AuthenticatedPrincipal(token, PrincipalKind.USER, allUserScopes, channel = PrincipalChannel.BROWSER)
                "alicekey" -> AuthenticatedPrincipal("alice", PrincipalKind.USER, setOf("waits:write"))
                "aliceapp" ->
                    AuthenticatedPrincipal(
                        "alice",
                        PrincipalKind.USER,
                        allUserScopes,
                        channel = PrincipalChannel.MOBILE_APP,
                    )
                "reader" -> AuthenticatedPrincipal("bob", PrincipalKind.SERVICE, setOf("waits:read"))
                "agent" -> AuthenticatedPrincipal("bob", PrincipalKind.DEVICE, setOf("waits:ask"))
                "writer" -> AuthenticatedPrincipal("bob", PrincipalKind.SERVICE, setOf("waits:write"))
                "davekey" -> AuthenticatedPrincipal("dave", PrincipalKind.SERVICE, setOf("waits:write"))
                "cloud" -> AuthenticatedPrincipal("bob", PrincipalKind.SERVICE, setOf("cloud:read"))
                "autoreader" -> AuthenticatedPrincipal("alice", PrincipalKind.SERVICE, setOf("automations:read"))
                "autowriter" -> AuthenticatedPrincipal("alice", PrincipalKind.SERVICE, setOf("automations:write"))
                "launch", "ended" ->
                    AuthenticatedPrincipal(
                        if (token == "launch") LAUNCH else "lch_0",
                        PrincipalKind.AGENT,
                        setOf("agent:session"),
                        orgId = ORG_A.toString(),
                    )
                else -> null
            }
        }
    }
    single<PrincipalOrgResolver> { TestOrgResolver }
    single<MemberDirectory> { TestMembers }
    single<MemberLogins> { TestLogins }
    single<AgentSessions> { TestSessions }
}

/** The launch of the `launch` agent token: bob's. */
const val LAUNCH = "lch_1"

/** The session of [LAUNCH]; [FOREIGN_SESSION] is another launch's. */
const val LAUNCH_SESSION = "s1"

/** Another launch's session. */
const val FOREIGN_SESSION = "s2"

/**
 * The launches module's reading of an agent token, as the waits MCP server sees it: [LAUNCH] is bob's, in
 * [LAUNCH_SESSION] only (another session is refused as the sessions module refuses it); any other token is no one.
 */
object FakeWaitAgents : WaitAgentResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, sessionId: String): WaitAgent? {
        if (principal.subjectId != LAUNCH) return null
        if (sessionId != LAUNCH_SESSION) throw OstromException(ErrorKind.FORBIDDEN, "This session is not yours.")
        return WaitAgent(ORG_A, BOB.toString(), LAUNCH_SESSION, listOf(LAUNCH_SESSION))
    }
}

/** The test identities' organizations. */
object TestOrgResolver : PrincipalOrgResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
        when (principal.subjectId) {
            "alice" -> OrgPrincipal(ORG_A, ALICE, OrgRole.MEMBER)
            "bob" -> OrgPrincipal(ORG_A, BOB, OrgRole.MEMBER)
            "dave" -> OrgPrincipal(ORG_A, DAVE, OrgRole.ADMIN)
            "carol" -> OrgPrincipal(ORG_B, CAROL, OrgRole.OWNER)
            else -> null
        }
}

/** A clock the test moves. */
class TestClock(var now: Instant = Instant.parse("2026-10-30T10:00:00Z")) : Clock {
    override fun now(): Instant = now
}

/** [WaitsRepository] in memory, with switches to lose races. */
@Suppress("ReturnCount") // The fake mirrors the SQL guards one early return at a time.
class InMemoryWaits : WaitsRepository {
    val waits = linkedMapOf<Uuid, Wait>()

    /** The answered waits of a session not handed to its agent yet, as the column `wake_pending`. */
    val wakePending = linkedSetOf<Uuid>()
    var loseNextAnswer = false
    var duplicateNext: Wait? = null

    override suspend fun createWithinLimit(wait: Wait, maxOpen: Int, sessions: List<String>): CreateOutcome {
        duplicateNext?.let { earlier ->
            duplicateNext = null
            if (earlier.id != wait.id) waits[earlier.id] = earlier
            return CreateOutcome.DUPLICATE
        }
        // The named sessions count with the wait's own, as the repository's.
        val session = wait.sessionId
        val scope = sessions + listOfNotNull(session)
        val open = waits.values.count {
            it.orgId == wait.orgId && it.status == WaitStatus.OPEN &&
                if (session == null) it.sessionId == null && it.createdBy == wait.createdBy else it.sessionId in scope
        }
        if (open >= maxOpen) return CreateOutcome.AT_LIMIT
        waits[wait.id] = wait
        return CreateOutcome.CREATED
    }

    override suspend fun findByIdempotencyKey(orgId: Uuid, createdBy: String, key: String): Wait? =
        waits.values.firstOrNull { it.orgId == orgId && it.createdBy == createdBy && it.idempotencyKey == key }

    override suspend fun get(orgId: Uuid, id: Uuid): Wait? = waits[id]?.takeIf { it.orgId == orgId }

    /** The people side of [WaitFilter], as the repository's query reads it. */
    private fun WaitFilter.whose(wait: Wait): Boolean = wait.orgId == orgId &&
        (assignee == null || wait.assignee == assignee) &&
        (involving == null || wait.assignee == involving || wait.createdBy == involving) &&
        (createdBy == null || wait.createdBy == createdBy)

    /** The state and place side of [WaitFilter]. */
    private fun WaitFilter.where(wait: Wait): Boolean = (status == null || wait.status == status) &&
        (sessionId == null || wait.sessionId == sessionId) &&
        (sessionIds == null || sessionIds.any { it == wait.sessionId }) &&
        (runId == null || wait.runId == runId)

    override suspend fun list(filter: WaitFilter, before: Uuid?, limit: Int): List<Wait> {
        val all = waits.values.reversed().filter { filter.whose(it) && filter.where(it) }
        // None after a row it does not hold, as the database.
        val start = before?.let { cursor -> all.indexOfFirst { it.id == cursor } + 1 } ?: 0
        return if (before != null && start == 0) emptyList() else all.drop(start).take(limit)
    }

    override suspend fun resolve(orgId: Uuid, id: Uuid, answer: WaitAnswer): Wait? {
        if (loseNextAnswer) {
            loseNextAnswer = false
            waits[id] = waits.getValue(id).copy(status = WaitStatus.CANCELLED, resolvedAt = answer.answeredAt)
            return null
        }
        return update(orgId, id) {
            it.copy(status = WaitStatus.RESOLVED, answer = answer, resolvedAt = answer.answeredAt)
        }?.also { if (it.sessionId != null) wakePending += it.id }
    }

    override suspend fun takeAnswered(orgId: Uuid, sessions: List<String>, limit: Int): List<Wait> {
        val taken = wakePending.map { waits.getValue(it) }
            .filter { it.orgId == orgId && it.sessionId in sessions }
            .sortedBy { it.resolvedAt }
            .take(limit)
        wakePending -= taken.map { it.id }.toSet()
        return taken
    }

    override suspend fun hasOpen(orgId: Uuid, sessions: List<String>): Boolean =
        waits.values.any { it.orgId == orgId && it.sessionId in sessions && it.status == WaitStatus.OPEN }

    override suspend fun cancel(orgId: Uuid, id: Uuid, now: Instant): Wait? =
        update(orgId, id) { it.copy(status = WaitStatus.CANCELLED, resolvedAt = now) }

    override suspend fun cancelOpenOfSessions(orgId: Uuid, sessions: List<String>, now: Instant): List<Wait> =
        waits.values.filter { it.orgId == orgId && it.sessionId in sessions }.mapNotNull { cancel(orgId, it.id, now) }

    private fun update(orgId: Uuid, id: Uuid, change: (Wait) -> Wait): Wait? {
        val wait = waits[id]?.takeIf { it.orgId == orgId && it.status == WaitStatus.OPEN } ?: return null
        return change(wait).also { waits[id] = it }
    }
}
