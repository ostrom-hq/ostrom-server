// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance

import dev.ostrom.server.governance.domain.AnswerConfirmationUseCaseImpl
import dev.ostrom.server.governance.domain.ConfirmationAnswer
import dev.ostrom.server.governance.domain.ConfirmationAuditUnavailableException
import dev.ostrom.server.governance.domain.ConfirmationClosedException
import dev.ostrom.server.governance.domain.ConfirmationCredential
import dev.ostrom.server.governance.domain.ConfirmationError
import dev.ostrom.server.governance.domain.ConfirmationExpiredException
import dev.ostrom.server.governance.domain.ConfirmationLimitException
import dev.ostrom.server.governance.domain.ConfirmationManagersOnlyException
import dev.ostrom.server.governance.domain.ConfirmationNotFoundException
import dev.ostrom.server.governance.domain.ConfirmationRules
import dev.ostrom.server.governance.domain.ConfirmationStaleException
import dev.ostrom.server.governance.domain.ConfirmationStatus
import dev.ostrom.server.governance.domain.ConfirmationStepUpRequiredException
import dev.ostrom.server.governance.domain.ExpireConfirmationsUseCase
import dev.ostrom.server.governance.domain.ExpireConfirmationsUseCaseImpl
import dev.ostrom.server.governance.domain.GetConfirmationUseCaseImpl
import dev.ostrom.server.governance.domain.GovernanceChanges
import dev.ostrom.server.governance.domain.OpenConfirmationUseCaseImpl
import dev.ostrom.server.governance.domain.RefusingConfirmationAudit
import dev.ostrom.server.governance.fakes.ClockWaits
import dev.ostrom.server.governance.fakes.FakeChange
import dev.ostrom.server.governance.fakes.FakeMembers
import dev.ostrom.server.governance.fakes.InMemoryConfirmations
import dev.ostrom.server.governance.fakes.RecordingAudit
import dev.ostrom.server.governance.fakes.TestClock
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.BrowserSessionRequiredException
import dev.ostrom.server.kernel.auth.GovernanceRequest
import dev.ostrom.server.kernel.auth.GovernanceScopes
import dev.ostrom.server.kernel.auth.GovernanceWrite
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Opening, reading, answering and expiring a governance confirmation, on in-memory fakes. */
class ConfirmationUseCasesTest {
    private val orgA = Uuid.random()
    private val orgB = Uuid.random()
    private val clock = TestClock()
    private val repository = InMemoryConfirmations()
    private val audit = RecordingAudit()
    private val waits = ClockWaits(clock)
    private val change = FakeChange()
    private val changes = GovernanceChanges(listOf(change))
    private val members = FakeMembers(
        mapOf(
            "ada" to (orgA to OrgRole.ADMIN),
            "olga" to (orgA to OrgRole.OWNER),
            "mia" to (orgA to OrgRole.MEMBER),
            "bob" to (orgB to OrgRole.OWNER),
        ),
    )
    private val expire = ExpireConfirmationsUseCaseImpl(repository, audit, waits, clock)
    private val open = OpenConfirmationUseCaseImpl(repository, members, changes, audit, expire, clock)
    private val get = GetConfirmationUseCaseImpl(repository, members, expire, waits, clock)
    private val answer = AnswerConfirmationUseCaseImpl(repository, members, changes, audit, expire, waits, clock)

    private fun token(who: String, scopes: Set<String> = GovernanceScopes.ALL) =
        AuthenticatedPrincipal(who, PrincipalKind.USER, scopes, credentialId = "tok_$who")

    private fun browser(who: String, signedInAgo: Duration? = 1.minutes) = AuthenticatedPrincipal(
        who,
        PrincipalKind.USER,
        setOf("user:*"),
        channel = PrincipalChannel.BROWSER,
        authenticatedAt = signedInAgo?.let { clock.now - it },
    )

    private fun request(who: String = "ada", to: String = "never", principal: AuthenticatedPrincipal = token(who)) =
        GovernanceRequest(
            principal,
            orgA,
            GovernanceScopes.GATES_POLICY,
            "PUT",
            "/api/v1/gates/policy",
            GovernanceWrite(FakeChange.KIND, JsonObject(mapOf("to" to JsonPrimitive(to)))),
        )

    private suspend fun confirm(who: AuthenticatedPrincipal, id: String) = answer(who, id, ConfirmationAnswer.CONFIRM)

    private suspend fun refuse(who: AuthenticatedPrincipal, id: String) = answer(who, id, ConfirmationAnswer.REFUSE)

    @Test
    fun anAdminsTokenOpensAConfirmationThatWritesNothing() = runTest {
        val opened = open(request())
        assertTrue(opened.id.startsWith("gcf_") && opened.id.length == 36, opened.id)
        assertEquals(ConfirmationStatus.PENDING, opened.status)
        assertEquals(ConfirmationCredential.ADMIN, opened.credential)
        assertEquals("tok_ada", opened.tokenId)
        assertEquals(clock.now + 15.minutes, opened.expiresAt)
        assertEquals(JsonPrimitive("sole-approver"), opened.before)
        assertEquals(JsonPrimitive("never"), opened.after)
        assertEquals("Set it", opened.summary)
        assertEquals("ada", opened.requestedBy.userId)
        assertEquals(opened, repository.rows[opened.id])
        assertEquals(listOf("CREATED ${opened.id}"), audit.steps)
        assertEquals(JsonPrimitive("sole-approver"), change.current, "Nothing written")
        val device = open(request("olga", principal = token("olga", setOf("rules:read"))))
        assertEquals(ConfirmationCredential.DEVICE, device.credential)
    }

    @Test
    fun theSameChangeAskedAgainIsTheSameConfirmationAndTenWaitAtMost() = runTest {
        val first = open(request())
        assertSame(first, open(request()), "Same person, same write")
        val other = open(request(to = "sole-approver"))
        assertTrue(other.id != first.id)
        repeat(ConfirmationRules.MAX_WAITING - 2) { open(request(to = "v$it")) }
        val limited = assertFailsWith<ConfirmationLimitException> { open(request(to = "one-more")) }
        assertEquals(ErrorKind.RATE_LIMITED, limited.kind)
        assertEquals("confirmation_limit", limited.type)
        open(request("olga", to = "one-more"))
        clock.now += 16.minutes
        open(request(to = "one-more"))
        assertEquals(ConfirmationRules.MAX_WAITING + 1, audit.steps.count { it.startsWith("EXPIRED") }, "Olga's too")
    }

    @Test
    fun onlyAnOwnerOrAnAdminOfTheOrganizationOpensOne() = runTest {
        assertFailsWith<ConfirmationManagersOnlyException> { open(request("mia")) }
        assertFailsWith<ConfirmationNotFoundException> { open(request("bob")) }
        assertFailsWith<ConfirmationNotFoundException> { open(request("nobody")) }
        change.refusePreview = OstromException(ErrorKind.NOT_FOUND, "Rule not found.")
        assertFailsWith<OstromException> { open(request()) }
        change.refusePreview = null
        val refusing =
            OpenConfirmationUseCaseImpl(repository, members, changes, RefusingConfirmationAudit(), expire, clock)
        assertFailsWith<ConfirmationAuditUnavailableException> { refusing(request()) }
        assertTrue(repository.rows.isEmpty(), "Nothing stored")
    }

    @Test
    fun theRequesterAndTheManagersReadItAndNobodyElse() = runTest {
        val opened = open(request())
        assertEquals(opened, get(token("ada", emptySet()), opened.id))
        assertEquals(opened, get(browser("olga"), opened.id))
        assertFailsWith<ConfirmationNotFoundException> { get(browser("mia"), opened.id) }
        assertFailsWith<ConfirmationNotFoundException> { get(browser("bob"), opened.id) }
        assertFailsWith<ConfirmationNotFoundException> { get(browser("nobody"), opened.id) }
        assertFailsWith<ConfirmationNotFoundException> { get(browser("ada"), "gcf_unknown") }
        val agent = AuthenticatedPrincipal("ada", PrincipalKind.AGENT, GovernanceScopes.ALL)
        assertFailsWith<ConfirmationNotFoundException> { get(agent, opened.id) }
        // A requester demoted to member still reads their own.
        val demoted = FakeMembers(mapOf("ada" to (orgA to OrgRole.MEMBER)))
        val reading = GetConfirmationUseCaseImpl(repository, demoted, expire, waits, clock)
        assertEquals(opened, reading(token("ada"), opened.id))
    }

    @Test
    fun aReadWaitsForTheAnswerAtMostThirtySeconds() = runTest {
        val opened = open(request())
        assertEquals(ConfirmationStatus.PENDING, get(token("ada"), opened.id, 2.minutes).status)
        assertEquals(30.seconds, waits.waited, "Bounded")
        waits.during = { refuse(browser("olga"), opened.id) }
        assertEquals(ConfirmationStatus.REFUSED, get(token("ada"), opened.id, 10.seconds).status)
        assertEquals(31.seconds, waits.waited, "Answered at the first look")
        waits.during = null
        assertEquals(ConfirmationStatus.REFUSED, get(token("ada"), opened.id, 10.seconds).status, "Answered: no wait")
        assertEquals(31.seconds, waits.waited)
        val late = open(request(to = "late"))
        clock.now += 15.minutes
        assertEquals(ConfirmationStatus.EXPIRED, get(token("ada"), late.id).status)
        assertEquals("EXPIRED ${late.id}", audit.steps.last())
        assertNull(audit.actors.last(), "The server expires it")
    }

    @Test
    fun aConfirmationWithAFreshSignInMakesTheWriteAsTheConfirmersDecisionOnce() = runTest {
        val opened = open(request())
        val confirmed = confirm(browser("olga"), opened.id)
        assertEquals(ConfirmationStatus.CONFIRMED, confirmed.status)
        assertEquals("olga", confirmed.answeredBy?.userId)
        assertEquals(clock.now, confirmed.answeredAt)
        assertEquals(JsonPrimitive("never"), confirmed.result)
        assertEquals(JsonPrimitive("never"), change.current)
        val confirmer = change.confirmers.single()
        assertEquals(orgA.toString(), confirmer.principal.namedOrg)
        assertEquals(OrgRole.OWNER, confirmer.member.role)
        assertEquals(listOf("CREATED", "CONFIRMED"), audit.steps.map { it.substringBefore(' ') })
        assertEquals(listOf("ada", "olga"), audit.actors)
        assertTrue(opened.id in waits.changed)
        assertFailsWith<ConfirmationClosedException> { confirm(browser("olga"), opened.id) }
        assertFailsWith<ConfirmationClosedException> { refuse(browser("ada"), opened.id) }
        assertEquals(1, change.confirmers.size, "Used once")
    }

    @Test
    fun aRefusalWritesNothingAndNeedsNoFreshSignIn() = runTest {
        val opened = open(request())
        val refused = refuse(browser("ada", signedInAgo = null), opened.id)
        assertEquals(ConfirmationStatus.REFUSED, refused.status)
        assertEquals(JsonPrimitive("sole-approver"), change.current)
        assertEquals("REFUSED ${opened.id}", audit.steps.last())
    }

    @Test
    fun onlyAManagersBrowserSessionAnswersAndAConfirmationNeedsAFreshSignIn() = runTest {
        val opened = open(request())
        assertFailsWith<BrowserSessionRequiredException> { confirm(token("olga"), opened.id) }
        val machine =
            AuthenticatedPrincipal("olga", PrincipalKind.SERVICE, setOf("user:*"), channel = PrincipalChannel.BROWSER)
        assertFailsWith<BrowserSessionRequiredException> { refuse(machine, opened.id) }
        assertFailsWith<ConfirmationManagersOnlyException> { refuse(browser("mia"), opened.id) }
        assertFailsWith<ConfirmationNotFoundException> { refuse(browser("bob"), opened.id) }
        assertFailsWith<ConfirmationNotFoundException> { refuse(browser("nobody"), opened.id) }
        assertFailsWith<ConfirmationNotFoundException> { refuse(browser("olga"), "gcf_x") }
        assertFailsWith<ConfirmationStepUpRequiredException> {
            confirm(browser("olga", signedInAgo = null), opened.id)
        }
        val old = assertFailsWith<ConfirmationStepUpRequiredException> {
            confirm(browser("olga", signedInAgo = 6.minutes), opened.id)
        }
        assertEquals("step_up_required", old.type)
        assertEquals(ConfirmationStatus.PENDING, repository.rows.getValue(opened.id).status, "Still waiting")
    }

    @Test
    fun anExpiredConfirmationIsGone() = runTest {
        val opened = open(request())
        clock.now += 15.minutes
        val gone = assertFailsWith<ConfirmationExpiredException> {
            confirm(browser("olga"), opened.id)
        }
        assertEquals(ErrorKind.GONE, gone.kind)
        assertEquals("confirmation_expired", gone.type)
        assertEquals(listOf("CREATED", "EXPIRED"), audit.steps.map { it.substringBefore(' ') })
        assertEquals(JsonPrimitive("sole-approver"), change.current)
    }

    @Test
    fun aChangeWhoseBeforeMovedIsClosedAsStaleAndWritesNothing() = runTest {
        val opened = open(request())
        change.current = JsonPrimitive("always")
        val stale = assertFailsWith<ConfirmationStaleException> {
            confirm(browser("olga"), opened.id)
        }
        assertEquals("confirmation_stale", stale.type)
        val failed = repository.rows.getValue(opened.id)
        assertEquals(ConfirmationStatus.FAILED, failed.status)
        assertEquals(ConfirmationError("confirmation_stale", stale.message), failed.error)
        assertEquals("FAILED ${opened.id}", audit.steps.last())
        assertTrue(change.confirmers.isEmpty())
        val gone = open(request(to = "x"))
        change.refusePreview = OstromException(ErrorKind.NOT_FOUND, "Rule not found.")
        val missing = assertFailsWith<OstromException> { confirm(browser("olga"), gone.id) }
        assertEquals(ErrorKind.NOT_FOUND, missing.kind)
        assertEquals(ConfirmationStatus.FAILED, repository.rows.getValue(gone.id).status)
    }

    @Test
    fun aConfirmedWriteThatIsRefusedFailsWithItsOwnError() = runTest {
        val opened = open(request())
        change.refuseApply = OstromException(ErrorKind.CONFLICT, "Version conflict.", "version_conflict")
        val refused = assertFailsWith<OstromException> { confirm(browser("olga"), opened.id) }
        assertEquals("version_conflict", refused.type)
        val failed = repository.rows.getValue(opened.id)
        assertEquals(ConfirmationStatus.FAILED, failed.status)
        assertEquals(ConfirmationError("version_conflict", "Version conflict."), failed.error)
        assertEquals(listOf("CREATED", "CONFIRMED", "FAILED"), audit.steps.map { it.substringBefore(' ') })
    }

    @Test
    fun ofTwoAnswersOnTwoInstancesOnlyOneCounts() = runTest {
        val opened = open(request())
        repository.loseNextAnswer = true
        assertFailsWith<ConfirmationClosedException> { refuse(browser("olga"), opened.id) }
        // Taken at its last second: by the time it is read again, it has expired.
        val late = open(request(to = "late"))
        clock.now = late.expiresAt - 1.seconds
        repository.loseNextAnswer = true
        repository.whileLosing = { clock.now = late.expiresAt }
        assertFailsWith<ConfirmationExpiredException> { refuse(browser("olga"), late.id) }
    }

    @Test
    fun theSweepExpiresEveryOrganizationsOverdueConfirmations() = runTest {
        val a = open(request())
        val b = OpenConfirmationUseCaseImpl(repository, members, changes, audit, expire, clock)(
            request("bob").copy(orgId = orgB),
        )
        assertEquals(0, expire.everywhere())
        clock.now += 15.minutes
        assertEquals(2, expire.everywhere())
        assertEquals(ConfirmationStatus.EXPIRED, repository.rows.getValue(a.id).status)
        assertEquals(ConfirmationStatus.EXPIRED, repository.rows.getValue(b.id).status)
        assertEquals(listOf(a.id, b.id), waits.changed)
        assertEquals(100, ExpireConfirmationsUseCase.ORGANIZATIONS_PER_SWEEP)
    }

    @Test
    fun eachKindHasOneModuleAndAnUnknownOneIsADefect() {
        assertFailsWith<IllegalArgumentException> { GovernanceChanges(listOf(FakeChange(), FakeChange())) }
        assertFailsWith<IllegalStateException> { changes.of("rules.update") }
        assertSame(change, changes.of(FakeChange.KIND))
    }
}
