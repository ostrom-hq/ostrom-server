// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import kotlinx.coroutines.test.runTest
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.ChangeCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.CloudAttribution
import run.ostrom.server.runners.domain.CloudEnvironmentSpec
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.CreateCloudSessionPayload
import run.ostrom.server.runners.domain.CreateCloudSessionUseCaseImpl
import run.ostrom.server.runners.domain.GitHubAccessMode
import run.ostrom.server.runners.domain.GitHubOwnerConsentException
import run.ostrom.server.runners.domain.ProfileUnassignedException
import run.ostrom.server.runners.domain.RepositoryCloudUsageMeter
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionProfile
import run.ostrom.server.runners.domain.SessionProfiles
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.FixedPlans
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.presentation.toResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Cloud session writes on behalf of another member (`runners.cloud.onbehalf.v1`, TeamCode's `CloudSessionService`):
 * the effective member owns what is created, the creation and each change are journaled with the caller as actor, with
 * the write (the repository's transaction).
 */
class OnBehalfSessionUseCasesTest {
    private val repository = InMemoryCloudSessionsRepository()
    private val plans = FixedPlans()
    private val clock = SettableClock()
    private val audit get() = repository.journal

    /** Bob may use the profile; an owner or an admin may use any. */
    private val profiles = object : SessionProfiles {
        override suspend fun choose(caller: Caller, profileId: Uuid?, image: String?): SessionProfile? {
            if (!caller.admin && caller.memberId != "bob") throw ProfileUnassignedException()
            return null
        }

        override suspend fun startFailure(session: CloudSession): SessionFailure? = null

        override suspend fun legacy(): SessionProfile? = null
    }
    private val create =
        CreateCloudSessionUseCaseImpl(repository, plans, RepositoryCloudUsageMeter(repository), clock, profiles)
    private val change =
        ChangeCloudSessionUseCaseImpl(repository, plans, RepositoryCloudUsageMeter(repository), clock)
    private val principal = AuthenticatedPrincipal("dana", PrincipalKind.USER, setOf("user:*"), credentialId = "cs-1")

    /** Dana, an admin, acting for [member]. */
    private fun dana(member: String = "bob", memberAdmin: Boolean = false) =
        Caller(ORG_A, member, true, "dana", "cs-1", principal, memberAdmin)

    private fun payload(environment: CloudEnvironmentSpec? = null) =
        CreateCloudSessionPayload("Fix it", "claude", null, environment = environment)

    @Test
    fun aSessionCreatedOnBehalfIsTheMembersWithoutGitHubAndRecordsWhoCreatedIt() = runTest {
        val created = create(dana(), payload())

        assertEquals("bob", created.ownerId)
        assertEquals(GitHubAccessMode.NONE, created.githubAccess)
        assertEquals(CloudAttribution("dana", "cs-1", "bob"), created.createdBy)
        assertEquals(CloudAttribution("dana", "cs-1", "bob"), repository.sessions.values.single().createdBy)
        assertEquals("dana", created.toResponse().createdBy?.initiatorId)
        val line = audit.single()
        assertEquals("cloud.session.created", line.event)
        assertEquals(principal, line.actor)
        assertEquals("cloud_session", line.subjectType)
        assertEquals(created.id.toString(), line.subjectId)
        assertEquals(
            mapOf("effective_member_id" to "bob", "credential_id" to "cs-1", "owner" to "bob"),
            line.details,
        )
    }

    @Test
    fun aMembersOwnSessionKeepsItsGitHubAccessAndTheServersCreationsAreNotJournaled() = runTest {
        val own = create(Caller(ORG_A, "bob", principal = principal), payload())
        assertEquals(GitHubAccessMode.OWNER, own.githubAccess)
        assertEquals(CloudAttribution("bob", null, "bob"), own.createdBy)
        assertEquals(1, audit.size)
        // A workflow run's or an automation's creation has no credential: nothing journaled here.
        create(Caller(ORG_A, "bob"), payload())
        assertEquals(1, audit.size)
    }

    @Test
    fun aSessionThatRequiresGitHubIsCreatedOnlyByItsOwner() = runTest {
        val github = CloudEnvironmentSpec(requireGitHub = true)
        assertFailsWith<GitHubOwnerConsentException> { create(dana(), payload(github)) }
        assertTrue(repository.sessions.isEmpty())
        assertTrue(audit.isEmpty())
        assertEquals("bob", create(Caller(ORG_A, "bob"), payload(github)).ownerId)
        // A recipe without GitHub is created on their behalf.
        assertEquals("bob", create(dana(), payload(CloudEnvironmentSpec())).ownerId)
    }

    @Test
    fun theProfileAssignmentResolvesForTheOwnerNotTheAdministratorWhoAsks() = runTest {
        // Carol is not assigned: dana's role does not lend her the profile.
        assertFailsWith<ProfileUnassignedException> { create(dana("carol"), payload()) }
        // Bob is assigned; an owner who is an admin passes.
        assertEquals("bob", create(dana("bob"), payload()).ownerId)
        assertEquals("carol", create(dana("carol", memberAdmin = true), payload()).ownerId)
    }

    @Test
    fun theIdempotencyKeyIsTheInitiatorsAndAReplayIsNotJournaledAgain() = runTest {
        val first = create.createKeyed(dana(), payload(), "press-1", "digest-a")
        val replay = create.createKeyed(dana(), payload(), "press-1", "digest-a")

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(Triple(ORG_A, "dana", "press-1"), repository.createKeys.keys.single())
        assertEquals(listOf("cloud.session.created"), audit.map { it.event })
        // Bob's own key of the same name is his.
        assertFalse(create.createKeyed(Caller(ORG_A, "bob"), payload(), "press-1", "digest-a").replayed)
    }

    @Test
    fun pauseResumeAndDeleteOnBehalfAreJournaledOnlyWhenTheyChangeSomething() = runTest {
        val running = session(state = SessionState.RUNNING, ownerId = "bob").also(repository::put)

        change.pause(dana(), running.id)
        change.pause(dana(), running.id)
        repository.put(repository.sessions.getValue(running.id).copy(state = SessionState.PAUSED))
        change.resume(dana(), running.id)
        change.resume(dana(), running.id)
        change.delete(dana(), running.id)
        change.delete(dana(), running.id)

        assertEquals(
            listOf("cloud.session.paused", "cloud.session.resumed", "cloud.session.deleted"),
            audit.map { it.event },
        )
        assertTrue(audit.all { it.details["effective_member_id"] == "bob" && it.details["owner"] == "bob" })
        assertTrue(audit.all { it.actor == principal && it.subjectId == running.id.toString() })
    }

    @Test
    fun aChangeOnBehalfReachesOnlyTheEffectiveMembersSessions() = runTest {
        val alices = session(state = SessionState.RUNNING, ownerId = "alice").also(repository::put)
        assertFailsWith<CloudSessionNotFoundException> { change.pause(dana(), alices.id) }
        assertNull(audit.firstOrNull())
    }
}
