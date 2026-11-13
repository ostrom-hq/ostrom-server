// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.CloudSessionStateException
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ExecutionFencedException
import run.ostrom.server.runners.domain.GitHubAccessMode
import run.ostrom.server.runners.domain.GitHubOwnerOnlyException
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.NoRunnerGitHubTokens
import run.ostrom.server.runners.domain.RunnerGitHubRequest
import run.ostrom.server.runners.domain.RunnerGitHubToken
import run.ostrom.server.runners.domain.RunnerGitHubTokenUseCaseImpl
import run.ostrom.server.runners.domain.RunnerGitHubTokens
import run.ostrom.server.runners.domain.RunnerUnauthenticatedException
import run.ostrom.server.runners.domain.SessionNotPausedException
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.SetCloudSessionGitHubAccessUseCaseImpl
import run.ostrom.server.runners.fakes.FakeRunnerTokens
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RunnerGitHubTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val tokens = FakeRunnerTokens()
    private val clock = SettableClock()
    private val audit = object : OrgAuditTrail {
        val records = mutableListOf<OrgAuditRecord>()

        override suspend fun record(record: OrgAuditRecord) {
            records += record
        }
    }

    /** The owner's link: alice is linked as octocat, anybody else is not. */
    private val links = object : RunnerGitHubTokens {
        val asked = mutableListOf<Pair<Uuid, String>>()

        override suspend fun available(): Boolean = true

        override suspend fun token(orgId: Uuid, memberId: String): RunnerGitHubToken {
            asked += orgId to memberId
            return if (memberId == "alice") {
                RunnerGitHubToken.Linked("ghu_alice", EXPIRY, "octocat")
            } else {
                RunnerGitHubToken.NotLinked
            }
        }
    }
    private val runner = RunnerGitHubTokenUseCaseImpl(repository, tokens, links)
    private val access = SetCloudSessionGitHubAccessUseCaseImpl(repository, audit, clock)
    private val alice = Caller(ORG_A, "alice")
    private val principal = AuthenticatedPrincipal("alice", PrincipalKind.USER, setOf("user:*"))

    /** A session of [state] whose execution 3 holds `token-x`. */
    private fun execution(
        state: SessionState = SessionState.RUNNING,
        desired: DesiredState = DesiredState.RUNNING,
        mode: GitHubAccessMode = GitHubAccessMode.OWNER,
        owner: String = "alice",
    ) = session(state, desired, ownerId = owner)
        .copy(epoch = 3, runnerTokenHash = tokens.hash("token-x"), githubAccess = mode)
        .also { repository.put(it) }

    @Test
    fun theRunnerOfTheCurrentExecutionGetsItsOwnersToken() = runTest {
        val states = listOf(SessionState.CREATING, SessionState.RESUMING, SessionState.RUNNING, SessionState.PAUSING)
        for (state in states) {
            repository.sessions.clear()
            val session = execution(state)
            val linked = assertIs<RunnerGitHubToken.Linked>(runner("token-x", RunnerGitHubRequest(session.id, 3)))
            assertEquals("ghu_alice" to "octocat", linked.token to linked.login, "$state")
            assertEquals("linked", linked.wire)
        }
        assertEquals(ORG_A to "alice", links.asked.last())
        val shown = RunnerGitHubToken.Linked("ghu_x", EXPIRY, "octocat").toString()
        assertEquals("Linked(octocat, expiresAt=$EXPIRY)", shown)

        repository.sessions.clear()
        val bob = execution(owner = "bob")
        assertEquals(RunnerGitHubToken.NotLinked, runner("token-x", RunnerGitHubRequest(bob.id, 3)))
    }

    @Test
    fun aSessionWithoutGitHubAccessGetsNothingWhateverItsOwnersLink() = runTest {
        val session = execution(mode = GitHubAccessMode.NONE)
        assertEquals(RunnerGitHubToken.NotLinked, runner("token-x", RunnerGitHubRequest(session.id, 3)))
        assertEquals(emptyList(), links.asked, "The link is not even read")
        assertEquals("not_linked", RunnerGitHubToken.NotLinked.wire)
        assertEquals("unavailable", RunnerGitHubToken.Unavailable.wire)
    }

    @Test
    fun anOldEndingOrOtherExecutionIsFenced() = runTest {
        val session = execution()
        assertFailsWith<RunnerUnauthenticatedException> { runner("token-y", RunnerGitHubRequest(session.id, 3)) }
        assertFailsWith<ExecutionFencedException> { runner("token-x", RunnerGitHubRequest(session.id, 2)) }
        assertFailsWith<ExecutionFencedException> { runner("token-x", RunnerGitHubRequest(Uuid.random(), 3)) }
        repository.sessions.clear()
        val paused = execution(SessionState.PAUSED)
        assertFailsWith<ExecutionFencedException> { runner("token-x", RunnerGitHubRequest(paused.id, 3)) }
        repository.sessions.clear()
        val deleting = execution(desired = DesiredState.TERMINATED)
        assertFailsWith<ExecutionFencedException> { runner("token-x", RunnerGitHubRequest(deleting.id, 3)) }
        assertEquals(emptyList(), links.asked)
    }

    @Test
    fun withoutTheGitHubIntegrationNobodyIsLinked() = runTest {
        assertFalse(NoRunnerGitHubTokens.available())
        assertEquals(RunnerGitHubToken.NotLinked, NoRunnerGitHubTokens.token(ORG_A, "alice"))
    }

    @Test
    fun theOwnerSharesOrRemovesTheSessionsAccessAndItIsAudited() = runTest {
        val session = execution()
        val shared = access(alice, principal, session.id, "shared")
        assertEquals(GitHubAccessMode.SHARED, shared.githubAccess)
        val record = audit.records.single()
        assertEquals("cloud.session.github_access_changed" to session.id.toString(), record.event to record.subjectId)
        assertEquals(mapOf("from" to "owner", "to" to "shared"), record.details)
        assertEquals(shared, access(alice, principal, session.id, "shared"), "Unchanged: nothing to audit")
        assertEquals(1, audit.records.size)

        assertFailsWith<SessionNotPausedException> { access(alice, principal, session.id, "none") }
        repository.put(repository.sessions.getValue(session.id).copy(state = SessionState.PAUSED))
        assertEquals(GitHubAccessMode.NONE, access(alice, principal, session.id, "none").githubAccess)
        assertEquals(GitHubAccessMode.OWNER, access(alice, principal, session.id, "owner").githubAccess)
        assertEquals(3, audit.records.size)
    }

    @Test
    fun anUnknownModeAnotherMembersOrAFinishedSessionChangesNothing() = runTest {
        val session = execution()
        assertFailsWith<InvalidCloudSessionRequestException> { access(alice, principal, session.id, "everyone") }
        for (kind in listOf(PrincipalKind.AGENT, PrincipalKind.SERVICE)) {
            val other = AuthenticatedPrincipal("alice", kind, setOf("cloud:write"))
            assertFailsWith<GitHubOwnerOnlyException>("$kind") { access(alice, other, session.id, "shared") }
        }
        assertFailsWith<CloudSessionNotFoundException> { access(Caller(ORG_A, "bob"), principal, session.id, "none") }
        repository.put(session.copy(state = SessionState.TERMINATED))
        assertFailsWith<CloudSessionStateException> { access(alice, principal, session.id, "shared") }
        assertNull(GitHubAccessMode.fromWire("everyone"))

        // Deleted between the read and the write.
        val live = execution()
        val vanishing = object : CloudSessionsRepository by repository {
            override suspend fun setGitHubAccess(orgId: Uuid, id: Uuid, access: GitHubAccessMode, now: Instant) =
                null
        }
        val racing = SetCloudSessionGitHubAccessUseCaseImpl(vanishing, audit, clock)
        assertFailsWith<CloudSessionNotFoundException> { racing(alice, principal, live.id, "shared") }
        // Resumed between the read and the write: `none` is refused as for a running session.
        repository.put(live.copy(state = SessionState.PAUSED))
        assertFailsWith<SessionNotPausedException> { racing(alice, principal, live.id, "none") }
        assertEquals(emptyList(), audit.records)
    }

    private companion object {
        val EXPIRY: Instant = Instant.parse("2026-10-30T18:00:00Z")
    }
}
