// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.fakes

import dev.ostrom.server.automations.domain.CreateOutcome
import dev.ostrom.server.automations.domain.MemberDirectory
import dev.ostrom.server.automations.domain.Wait
import dev.ostrom.server.automations.domain.WaitAnswer
import dev.ostrom.server.automations.domain.WaitCaller
import dev.ostrom.server.automations.domain.WaitEvents
import dev.ostrom.server.automations.domain.WaitFilter
import dev.ostrom.server.automations.domain.WaitStatus
import dev.ostrom.server.automations.domain.WaitsRepository
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.TokenAuthenticator
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

/**
 * The test identities: `alice`, `bob` (members of org A), `dave` (admin of org A), `carol` (owner of org B) and
 * `nobody` (no org) sign in themselves; `reader`, `agent` and `writer` are bob's tokens with `waits:read`,
 * `waits:ask` and `waits:write`; `davekey` is dave's with `waits:write`; `cloud` is bob's with no waits scope.
 */
val testIdentities = module {
    single<TokenAuthenticator> {
        object : TokenAuthenticator {
            override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
                "alice", "bob", "dave", "carol", "nobody" ->
                    AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("user:*"))
                "reader" -> AuthenticatedPrincipal("bob", PrincipalKind.SERVICE, setOf("waits:read"))
                "agent" -> AuthenticatedPrincipal("bob", PrincipalKind.DEVICE, setOf("waits:ask"))
                "writer" -> AuthenticatedPrincipal("bob", PrincipalKind.SERVICE, setOf("waits:write"))
                "davekey" -> AuthenticatedPrincipal("dave", PrincipalKind.SERVICE, setOf("waits:write"))
                "cloud" -> AuthenticatedPrincipal("bob", PrincipalKind.SERVICE, setOf("cloud:read"))
                else -> null
            }
        }
    }
    single<PrincipalOrgResolver> { TestOrgResolver }
    single<MemberDirectory> { TestMembers }
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
    var loseNextAnswer = false
    var duplicateNext: Wait? = null

    override suspend fun createWithinLimit(wait: Wait, maxOpen: Int): CreateOutcome {
        duplicateNext?.let { earlier ->
            duplicateNext = null
            if (earlier.id != wait.id) waits[earlier.id] = earlier
            return CreateOutcome.DUPLICATE
        }
        val open = waits.values.count {
            it.orgId == wait.orgId && it.status == WaitStatus.OPEN && it.sessionId == wait.sessionId &&
                (wait.sessionId != null || it.createdBy == wait.createdBy)
        }
        if (open >= maxOpen) return CreateOutcome.AT_LIMIT
        waits[wait.id] = wait
        return CreateOutcome.CREATED
    }

    override suspend fun findByIdempotencyKey(orgId: Uuid, createdBy: String, key: String): Wait? =
        waits.values.firstOrNull { it.orgId == orgId && it.createdBy == createdBy && it.idempotencyKey == key }

    override suspend fun get(orgId: Uuid, id: Uuid): Wait? = waits[id]?.takeIf { it.orgId == orgId }

    override suspend fun list(filter: WaitFilter, before: Uuid?, limit: Int): List<Wait> {
        val all = waits.values.reversed().filter {
            it.orgId == filter.orgId &&
                (filter.assignee == null || it.assignee == filter.assignee) &&
                (filter.involving == null || it.assignee == filter.involving || it.createdBy == filter.involving) &&
                (filter.status == null || it.status == filter.status) &&
                (filter.sessionId == null || it.sessionId == filter.sessionId) &&
                (filter.runId == null || it.runId == filter.runId)
        }
        val start = before?.let { cursor -> all.indexOfFirst { it.id == cursor } + 1 } ?: 0
        return all.drop(start).take(limit)
    }

    override suspend fun resolve(orgId: Uuid, id: Uuid, answer: WaitAnswer): Wait? {
        if (loseNextAnswer) {
            loseNextAnswer = false
            waits[id] = waits.getValue(id).copy(status = WaitStatus.CANCELLED, resolvedAt = answer.answeredAt)
            return null
        }
        return update(orgId, id) {
            it.copy(status = WaitStatus.RESOLVED, answer = answer, resolvedAt = answer.answeredAt)
        }
    }

    override suspend fun cancel(orgId: Uuid, id: Uuid, now: Instant): Wait? =
        update(orgId, id) { it.copy(status = WaitStatus.CANCELLED, resolvedAt = now) }

    private fun update(orgId: Uuid, id: Uuid, change: (Wait) -> Wait): Wait? {
        val wait = waits[id]?.takeIf { it.orgId == orgId && it.status == WaitStatus.OPEN } ?: return null
        return change(wait).also { waits[id] = it }
    }
}
