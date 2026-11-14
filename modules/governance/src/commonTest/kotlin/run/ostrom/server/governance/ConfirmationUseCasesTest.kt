// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.governance

import run.ostrom.server.governance.domain.AnswerConfirmationUseCaseImpl
import run.ostrom.server.governance.domain.ConfirmationAnswer
import run.ostrom.server.governance.domain.ConfirmationClosedException
import run.ostrom.server.governance.domain.ConfirmationCredential
import run.ostrom.server.governance.domain.ConfirmationError
import run.ostrom.server.governance.domain.ConfirmationExpiredException
import run.ostrom.server.governance.domain.ConfirmationLimitException
import run.ostrom.server.governance.domain.ConfirmationManagersOnlyException
import run.ostrom.server.governance.domain.ConfirmationNotFoundException
import run.ostrom.server.governance.domain.ConfirmationRules
import run.ostrom.server.governance.domain.ConfirmationSecrets
import run.ostrom.server.governance.domain.ConfirmationStaleException
import run.ostrom.server.governance.domain.ConfirmationRecords
import run.ostrom.server.governance.domain.ConfirmationStatus
import run.ostrom.server.governance.domain.ConfirmationStepUpRequiredException
import run.ostrom.server.governance.domain.ExpireConfirmationsUseCase
import run.ostrom.server.governance.domain.ExpireConfirmationsUseCaseImpl
import run.ostrom.server.governance.domain.GetConfirmationUseCaseImpl
import run.ostrom.server.governance.domain.GovernanceChanges
import run.ostrom.server.governance.domain.OpenConfirmationUseCaseImpl
import run.ostrom.server.governance.fakes.ClockWaits
import run.ostrom.server.governance.fakes.FakeChange
import run.ostrom.server.governance.fakes.FakeMembers
import run.ostrom.server.governance.fakes.InMemoryConfirmations
import run.ostrom.server.governance.fakes.RecordingAudit
import run.ostrom.server.governance.fakes.TestClock
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.BrowserSessionRequiredException
import run.ostrom.server.kernel.auth.GovernanceRequest
import run.ostrom.server.kernel.auth.GovernanceScopes
import run.ostrom.server.kernel.auth.GovernanceWrite
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlinx.coroutines.CancellationException
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
    private val secrets = ConfirmationSecrets(changes, repository)
    private val get = GetConfirmationUseCaseImpl(repository, members, expire, waits, clock, secrets)
    private val answer = AnswerConfirmationUseCaseImpl(
        ConfirmationRecords(repository, audit, waits),
        members,
        changes,
        expire,
        clock,
        secrets,
    )

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
        assertNull(device.tokenName)
    }

    @Test
    fun aPersonalTokenIsNamedWhateverItsScopesAndNeverPassesForTheClis() = runTest {
        val governing = token("ada").copy(personalTokenName = "deploy bot")
        val opened = open(request("ada", principal = governing))
        assertEquals(ConfirmationCredential.API_TOKEN, opened.credential)
        assertEquals("tok_ada" to "deploy bot", opened.tokenId to opened.tokenName)
        val reader = token("olga", setOf("rules:read")).copy(personalTokenName = "laptop")
        val plain = open(request("olga", principal = reader))
        assertEquals(ConfirmationCredential.API_TOKEN to "laptop", plain.credential to plain.tokenName)
    }

    @Test
    fun theSameChangeAskedAgainIsTheSameConfirmationAndTenWaitAtMost() = runTest {
        val first = open(request())
        assertSame(first, open(request()), "Same person, same write")
        val other = open(request(to = "sole-approver"))
        assertTrue(other.id != first.id)
        // The same write on another request is another change.
        val elsewhere = open(request().copy(path = "/api/v1/gates/policy/other"))
        val otherwise = open(request().copy(method = "PATCH"))
        assertEquals(4, setOf(first.id, other.id, elsewhere.id, otherwise.id).size)
        repeat(ConfirmationRules.MAX_WAITING - 4) { open(request(to = "v$it")) }
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
        audit.failWith = IllegalStateException("The audit log is down.")
        assertFailsWith<IllegalStateException> { open(request()) }
        assertTrue(repository.rows.isEmpty(), "Not audited: nothing stored")
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
        val reading = GetConfirmationUseCaseImpl(repository, demoted, expire, waits, clock, secrets)
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
    fun aConfirmedWriteStillBeingMadeIsWaitedOnUntilItsResultIsWritten() = runTest {
        // confirmed, its result not written yet: the CLI waiting here must not read a confirmation with no result
        // (it would say an invitation sent invited nobody)
        val opened = open(request())
        val making = opened.copy(status = ConfirmationStatus.CONFIRMED, settled = false)
        repository.rows[opened.id] = making
        assertEquals(ConfirmationStatus.PENDING, making.shownAt(clock.now))
        assertEquals(ConfirmationStatus.CONFIRMED, making.statusAt(clock.now), "Stored as confirmed")
        clock.now += 16.minutes
        assertEquals(ConfirmationStatus.PENDING, making.shownAt(clock.now), "Never expired once confirmed")
        val unsettled = get(token("ada"), opened.id, 3.seconds)
        assertEquals(false, unsettled.settled)
        assertEquals(3.seconds, waits.waited, "Waited on, as a pending one")
        waits.during = { repository.settle(orgA, opened.id, JsonPrimitive("never"), null) }
        val settled = get(token("ada"), opened.id, 10.seconds)
        assertEquals(ConfirmationStatus.CONFIRMED to JsonPrimitive("never"), settled.status to settled.result)
        assertEquals(ConfirmationStatus.CONFIRMED, settled.shownAt(clock.now))
    }

    @Test
    fun aConfirmedWriteWhoseResultWasLostFailsFiveMinutesPastItsExpiry() = runTest {
        // its server stopped between the answer and the result: failed, result_lost, never pending for ever
        val opened = open(request())
        repository.rows[opened.id] = opened.copy(status = ConfirmationStatus.CONFIRMED, settled = false)
        clock.now += ConfirmationRules.LIFETIME + ConfirmationRules.RESULT_GRACE - 1.seconds
        assertEquals(ConfirmationStatus.PENDING, get(token("ada"), opened.id).shownAt(clock.now), "Still being made")
        clock.now += 1.seconds
        val lost = get(token("ada"), opened.id)
        assertEquals(ConfirmationStatus.FAILED, lost.shownAt(clock.now))
        assertEquals(ConfirmationRules.RESULT_LOST, lost.error?.type)
        assertEquals("FAILED ${opened.id}", audit.steps.last())
        assertNull(audit.actors.last(), "The server fails it")
        assertTrue(opened.id in waits.changed, "A waiting read is woken")
        // a result that comes after never turns it back into a written one
        repository.settle(orgA, opened.id, JsonPrimitive("never"), null)
        assertNull(repository.rows.getValue(opened.id).result)
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
        assertEquals(listOf<String?>("ada", "olga"), audit.actors)
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
    fun aConfirmedWriteThatCrashesIsSettledAsFailedNeverAsWritten() = runTest {
        val opened = open(request())
        change.refuseApply = IllegalStateException("database")
        assertFailsWith<IllegalStateException> { confirm(browser("olga"), opened.id) }
        val failed = repository.rows.getValue(opened.id)
        assertEquals(ConfirmationStatus.FAILED, failed.status)
        assertEquals("internal_error", failed.error?.type)
        assertEquals(listOf("CREATED", "CONFIRMED", "FAILED"), audit.steps.map { it.substringBefore(' ') })
        val cancelled = open(request(to = "cancelled"))
        change.refuseApply = CancellationException("stopping")
        assertFailsWith<CancellationException> { confirm(browser("olga"), cancelled.id) }
        assertEquals(ConfirmationStatus.CONFIRMED, repository.rows.getValue(cancelled.id).status, "Cancellation passes")
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
        // A row is never deleted: one gone meanwhile is a server fault, never read as answered.
        val gone = open(request(to = "gone"))
        repository.loseNextAnswer = true
        repository.whileLosing = { repository.rows.remove(gone.id) }
        val fault = assertFailsWith<IllegalStateException> { refuse(browser("olga"), gone.id) }
        assertEquals("A confirmation is never deleted.", fault.message)
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
    fun aFailedExpiryEventCostsTheOthersNothing() = runTest {
        val first = open(request())
        val second = open(request(to = "other"))
        clock.now += 15.minutes
        audit.failWith = IllegalStateException("The audit log is down.")
        assertFailsWith<IllegalStateException> { expire.inOrganization(orgA) }
        assertEquals(listOf(first.id, second.id), waits.changed, "Both expired and woken")
        val third = run {
            audit.failWith = null
            open(request(to = "third"))
        }
        clock.now += 15.minutes
        audit.failWith = CancellationException("stopping")
        assertFailsWith<CancellationException> { expire.inOrganization(orgA) }
        assertEquals(ConfirmationStatus.EXPIRED, repository.rows.getValue(third.id).status)
    }

    @Test
    fun aConfirmationThatExpiresDuringAWaitIsWrittenExpiredOnce() = runTest {
        val opened = open(request())
        clock.now = opened.expiresAt - 3.seconds
        assertEquals(ConfirmationStatus.EXPIRED, get(token("ada"), opened.id, 30.seconds).status)
        assertEquals(3.seconds, waits.waited, "Each look reads the time, not the table")
        assertEquals(ConfirmationStatus.EXPIRED, repository.rows.getValue(opened.id).status)
        assertEquals("EXPIRED ${opened.id}", audit.steps.last())
    }

    @Test
    fun eachKindHasOneModuleAndAnUnknownOneIsADefect() {
        assertFailsWith<IllegalArgumentException> { GovernanceChanges(listOf(FakeChange(), FakeChange())) }
        assertFailsWith<IllegalStateException> { changes.of("rules.update") }
        assertSame(change, changes.of(FakeChange.KIND))
    }

    @Test
    fun aResultsSecretIsShownOnceToThePersonWhoAskedAndNeverToTheConsole() = runTest {
        change.secret = true
        val asked = open(request())
        assertNull(get(token("ada"), asked.id).result, "Pending: no result yet")
        val answered = confirm(browser("olga"), asked.id)
        assertEquals(JsonPrimitive("shown"), answered.result, "The owner who confirmed never reads it")
        assertEquals(JsonPrimitive("shown"), get(browser("olga"), asked.id).result, "Nor reads it later")
        assertEquals(JsonPrimitive("shown"), get(browser("ada"), asked.id).result, "Nor the asker's own console")
        assertEquals(JsonPrimitive("never"), repository.rows.getValue(asked.id).result, "Kept for the token that waits")
        assertEquals(JsonPrimitive("never"), get(token("ada"), asked.id).result, "The person who asked reads it once")
        assertEquals(JsonPrimitive("shown"), repository.rows.getValue(asked.id).result, "Then it is gone")
        assertEquals(JsonPrimitive("shown"), get(token("ada"), asked.id).result, "Their second read")
        // Confirmed on the console by the person who asked: their console's answer does not burn the CLI's read.
        val own = open(request(to = "always"))
        assertEquals(JsonPrimitive("shown"), confirm(browser("ada"), own.id).result)
        assertEquals(JsonPrimitive("always"), get(token("ada"), own.id).result)
    }

    @Test
    fun aResultOfAKindNoModuleOwnsAnyMoreIsReadAsStored() = runTest {
        change.secret = true
        val asked = open(request())
        confirm(browser("olga"), asked.id)
        val orphan = repository.rows.getValue(asked.id).let { it.copy(write = it.write.copy(kind = "gone.away")) }
        repository.rows[asked.id] = orphan
        assertEquals(JsonPrimitive("never"), get(browser("olga"), asked.id).result)
        assertNull(changes.ownerOf("gone.away"))
    }
}
