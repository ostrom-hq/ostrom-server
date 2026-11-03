// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.notifications.domain.PushCredential
import dev.ostrom.server.notifications.domain.PushDevice
import dev.ostrom.server.notifications.domain.PushDevicesSignOutListener
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PushDevicesSignOutListenerTest {
    private val user = Uuid.random()
    private val orgs = listOf(Uuid.random(), Uuid.random())
    private val repository = InMemoryPushDevicesRepository()
    private val listener = PushDevicesSignOutListener(repository) { if (it == user) orgs else emptyList() }

    private fun phone(org: Uuid, credential: PushCredential) = PushDevice(
        Uuid.random(), org, Uuid.random(), "ExponentPushToken[${Uuid.random()}]", "expo", "ios", null,
        Instant.DISTANT_PAST, Instant.DISTANT_PAST, credential,
    ).also { repository.devices += it }

    @Test
    fun signingOutForgetsThePhonesOfThatCredentialInEveryOrganization() = runTest {
        val app = PushCredential(user, Uuid.random(), browser = false)
        val other = PushCredential(user, Uuid.random(), browser = false)
        orgs.forEach { phone(it, app) }
        val kept = phone(orgs[0], other)

        listener.signedOut(user.toString(), app.credentialId.toString())

        assertEquals(listOf(kept), repository.devices)
    }

    @Test
    fun signingOutEverywhereForgetsThePhonesOfEveryBrowserSessionOnly() = runTest {
        val app = phone(orgs[1], PushCredential(user, Uuid.random(), browser = false))
        phone(orgs[0], PushCredential(user, Uuid.random(), browser = true))
        phone(orgs[1], PushCredential(user, Uuid.random(), browser = true))

        listener.signedOut(user.toString(), null)

        assertEquals(listOf(app), repository.devices)
    }

    @Test
    fun aSubjectOrCredentialThatIsNoIdForgetsNothing() = runTest {
        val app = PushCredential(user, Uuid.random(), browser = false)
        val device = phone(orgs[0], app)

        listener.signedOut("service-key", app.credentialId.toString())
        listener.signedOut(user.toString(), "not-a-uuid")

        assertEquals(listOf(device), repository.devices)
    }
}
