// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.notifications.domain.PushRelayIdentitiesRepository
import run.ostrom.server.notifications.domain.PushRelayIdentity
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The instance's identities at the push relays on PostgreSQL: one per relay, the first replica's kept. */
class PushRelayIdentitiesPostgresTest {
    private val relay = "https://push.example.com"

    @Test
    fun theFirstIdentityKeptAtARelayIsTheOneEveryReplicaSignsWith() = serverTest(listOf(NotificationsModule)) {
        val identities = it.koin.get<PushRelayIdentitiesRepository>()
        assertNull(identities.find(relay))

        val first = PushRelayIdentity("key-1", "sealed-1", null)
        assertEquals(first, identities.create(relay, first))
        assertEquals(first, identities.create(relay, PushRelayIdentity("key-2", "sealed-2", null)), "A late replica")

        identities.setInstance(relay, "key-1", "key-1")
        identities.setInstance(relay, "key-2", "key-2")
        assertEquals(first.copy(instanceId = "key-1"), identities.find(relay), "Another key's id is not taken")
        identities.setInstance(relay, "key-1", null)
        assertEquals(first, identities.find(relay))
        assertNull(identities.find("https://other.example.com"))
    }
}
