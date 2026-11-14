// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.notifications.domain.EncryptedPush
import run.ostrom.server.notifications.domain.PushRelayIdentitiesRepository
import run.ostrom.server.notifications.domain.PushResult
import run.ostrom.server.notifications.domain.RelaySenders
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.notifications.domain.PushRelayIdentity
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    @Test
    fun theServersRelaySenderKeepsItsSealedKeyAndRetriesARelayThatDoesNotAnswer() =
        serverTest(listOf(SecretsModule, NotificationsModule)) {
            // Nothing listens on port 1 of the loopback, which the outbound client allows for a relay of one's own.
            val nowhere = "https://127.0.0.1:1"
            val sender = it.koin.get<RelaySenders>().sender(nowhere)
            val push = EncryptedPush("fcm-token", "android", "c2VhbGVk", "0123456789abcdef0123456789abcdef", 3600)

            assertEquals(PushResult.retry("relay_registration"), sender.send(push))
            val identity = assertNotNull(it.koin.get<PushRelayIdentitiesRepository>().find(nowhere))
            assertNull(identity.instanceId, "Not registered yet")
            assertFalse(identity.sealedPrivateKey.contains(identity.publicKey), "Sealed")
        }
}
