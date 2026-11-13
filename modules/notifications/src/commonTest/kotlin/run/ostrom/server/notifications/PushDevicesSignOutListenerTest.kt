// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.notifications.domain.PushCredential
import run.ostrom.server.notifications.domain.PushDevice
import run.ostrom.server.notifications.domain.PushDevicesRepository
import run.ostrom.server.notifications.domain.PushDevicesSignOutListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PushDevicesSignOutListenerTest {
    private val user = Uuid.random()
    private val orgs = listOf(Uuid.random(), Uuid.random())
    private val repository = InMemoryPushDevicesRepository()
    private val logger = RecordingLogger()
    private val listener = PushDevicesSignOutListener(repository, { if (it == user) orgs else emptyList() }, logger)

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

    @Test
    fun anOrganizationThatFailsIsLoggedAndTheOthersAreStillCleaned() = runTest {
        val app = PushCredential(user, Uuid.random(), browser = false)
        orgs.forEach { phone(it, app) }
        val failing = object : PushDevicesRepository by repository {
            override suspend fun forget(orgId: Uuid, userId: Uuid, credentialId: Uuid?) {
                if (orgId == orgs[0]) error("database down")
                repository.forget(orgId, userId, credentialId)
            }
        }

        PushDevicesSignOutListener(failing, { orgs }, logger).signedOut(user.toString(), app.credentialId.toString())

        assertEquals(listOf(orgs[0]), repository.devices.map { it.orgId })
        val (level, message, fields) = logger.events.single()
        assertEquals(LogLevel.WARN, level)
        assertEquals("push devices not forgotten at sign-out", message)
        assertEquals(mapOf("org_id" to orgs[0].toString(), "error" to "IllegalStateException"), fields)
    }

    @Test
    fun aCancelledSignOutStaysCancelled() = runTest {
        val cancelled = object : PushDevicesRepository by repository {
            override suspend fun forget(orgId: Uuid, userId: Uuid, credentialId: Uuid?) =
                throw CancellationException("stop")
        }

        assertFailsWith<CancellationException> {
            PushDevicesSignOutListener(cancelled, { orgs }, logger).signedOut(user.toString(), null)
        }
        assertTrue(logger.events.isEmpty())
    }
}
