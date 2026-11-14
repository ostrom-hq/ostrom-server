// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.ArtifactPublishForbiddenException
import run.ostrom.server.artifacts.domain.ArtifactPublishRateLimitedException
import run.ostrom.server.artifacts.domain.PublicationGateImpl
import run.ostrom.server.artifacts.domain.storage.ArtifactQuotas
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
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** TC-ART-29: who publishes and how often, by the organization's settings (TeamCode's commit rules). */
class PublicationGateTest {
    private val storage = InMemoryStorage().active(ORG_A)
    private val publications = InMemoryPublications()
    private val clock = TestClock()
    private val gate = PublicationGateImpl(storage, publications, clock)

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
            gate.published(alice, Uuid.random(), clock.now)
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
    fun nothingIsPublishedWhileArtifactsAreInactive() = runTest {
        assertFailsWith<ArtifactsInactiveException> { gate.admit(carol) }
        assertEquals(emptyList(), publications.published)
    }
}
