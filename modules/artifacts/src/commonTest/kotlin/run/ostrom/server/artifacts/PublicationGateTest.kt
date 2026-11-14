// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactLifecycleRepository
import run.ostrom.server.artifacts.domain.ArtifactPublishForbiddenException
import run.ostrom.server.artifacts.domain.ArtifactPublishRateLimitedException
import run.ostrom.server.artifacts.domain.ArtifactQuotaExceededException
import run.ostrom.server.artifacts.domain.ArtifactUsage
import run.ostrom.server.artifacts.domain.PublicationGateImpl
import run.ostrom.server.artifacts.domain.storage.ArtifactQuotas
import run.ostrom.server.artifacts.domain.storage.ArtifactRetention
import run.ostrom.server.artifacts.domain.storage.ArtifactsInactiveException
import run.ostrom.server.artifacts.domain.storage.WhoCanPublish
import run.ostrom.server.artifacts.fakes.InMemoryPublications
import run.ostrom.server.artifacts.fakes.InMemoryStorage
import run.ostrom.server.artifacts.fakes.ORG_A
import run.ostrom.server.artifacts.fakes.TestClock
import run.ostrom.server.artifacts.fakes.active
import run.ostrom.server.artifacts.fakes.alice
import run.ostrom.server.artifacts.fakes.bob
import run.ostrom.server.artifacts.fakes.carol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** TC-ART-29: who publishes and how often, by the organization's settings (TeamCode's commit rules). */
class PublicationGateTest {
    private val storage = InMemoryStorage().active(ORG_A)
    private val publications = InMemoryPublications()
    private val clock = TestClock()
    private var usage = ArtifactUsage(0, 0, 0)
    private val lifecycle = RecordedLifecycle()
    private val gate = PublicationGateImpl(storage, publications, { _, _ -> usage }, lifecycle, clock)

    /** What the gate asked to keep and expire. */
    private class RecordedLifecycle : ArtifactLifecycleRepository {
        val retained = mutableListOf<Pair<Int, Instant?>>()
        val expired = mutableListOf<Instant?>()

        override suspend fun retain(orgId: Uuid, artifactId: Uuid, versions: Int, expiresAt: Instant?, now: Instant) {
            retained += versions to expiresAt
        }

        override suspend fun expire(orgId: Uuid, artifactId: Uuid, expiresAt: Instant?) {
            expired += expiresAt
        }
    }

    private fun artifact(access: ArtifactAccess = ArtifactAccess.TEAM) =
        Artifact(Uuid.random(), ORG_A, alice.memberId, "T", access, 1, null, clock.now, clock.now)

    private fun settle(who: WhoCanPublish = WhoCanPublish.EVERYONE, perHour: Int = ArtifactQuotas().publishesPerHour) {
        val settings = storage.settings.getValue(ORG_A)
        val quotas = settings.quotas.copy(publishesPerHour = perHour)
        storage.settings[ORG_A] = settings.copy(whoCanPublish = who, quotas = quotas)
    }

    @Test
    fun whenOnlyAdministratorsPublishAMemberIsRefused() = runTest {
        settle(WhoCanPublish.ADMINS)
        assertFailsWith<ArtifactPublishForbiddenException> { gate.admit(alice) }
        gate.admit(alice.copy(administrator = true))
        settle(WhoCanPublish.EVERYONE)
        gate.admit(alice)
    }

    @Test
    fun aMemberPublishesSoManyVersionsAnHour() = runTest {
        settle(perHour = 2)
        repeat(2) {
            gate.admit(alice)
            gate.published(alice, artifact(), clock.now)
            clock.now += 10.minutes
        }
        val limited = assertFailsWith<ArtifactPublishRateLimitedException> { gate.admit(alice) }
        // The first leaves the hour in 40 minutes.
        assertEquals(40L * 60 + 1, limited.retryAfterSeconds)
        // Another member counts apart.
        gate.admit(bob)
        clock.now += 41.minutes
        gate.admit(alice)
    }

    @Test
    fun aPublicationStaysWithinTheOwnersAndTheOrganizationsQuotas() = runTest {
        val quotas = ArtifactQuotas(memberBytes = 100, orgBytes = 1000, artifactsPerMember = 2)
        storage.settings[ORG_A] = storage.settings.getValue(ORG_A).copy(quotas = quotas)
        usage = ArtifactUsage(ownerBytes = 90, orgBytes = 900, ownerArtifacts = 1)
        gate.withinQuotas(ORG_A, alice.memberId, 10, newArtifact = true)
        val refused = listOf(
            Triple(ArtifactUsage(90, 900, 2), 0L, true),
            Triple(ArtifactUsage(90, 900, 1), 11L, false),
            Triple(ArtifactUsage(10, 995, 1), 10L, false),
        )
        for ((held, added, new) in refused) {
            usage = held
            assertFailsWith<ArtifactQuotaExceededException> { gate.withinQuotas(ORG_A, alice.memberId, added, new) }
        }
        // A version of an artifact already counted is not a new one.
        usage = ArtifactUsage(0, 0, 2)
        gate.withinQuotas(ORG_A, alice.memberId, 0, newArtifact = false)
    }

    @Test
    fun nothingIsPublishedWhileArtifactsAreInactive() = runTest {
        assertFailsWith<ArtifactsInactiveException> { gate.admit(carol) }
        assertEquals(emptyList(), publications.published)
    }

    @Test
    fun aPublicationKeepsTheNewestVersionsAndExpiresByItsAccess() = runTest {
        val retention = ArtifactRetention(personalDays = 7, sharedDays = 30, maxVersionsPerArtifact = 5)
        storage.settings[ORG_A] = storage.settings.getValue(ORG_A).copy(retention = retention)
        gate.published(alice, artifact(ArtifactAccess.PERSONAL), clock.now)
        gate.published(alice, artifact(ArtifactAccess.LINK), clock.now)
        assertEquals(listOf(5 to clock.now + 7.days, 5 to clock.now + 30.days), lifecycle.retained)
        gate.accessChanged(artifact(ArtifactAccess.TEAM), clock.now)
        storage.settings[ORG_A] = storage.settings.getValue(ORG_A).copy(retention = ArtifactRetention())
        gate.accessChanged(artifact(ArtifactAccess.PERSONAL), clock.now)
        assertEquals(listOf(clock.now + 30.days, null), lifecycle.expired)
    }
}
