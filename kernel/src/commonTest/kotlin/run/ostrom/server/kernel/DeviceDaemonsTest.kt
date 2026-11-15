// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.auth.DaemonFolder
import run.ostrom.server.kernel.auth.DaemonListingRequest
import run.ostrom.server.kernel.auth.DaemonPresence
import run.ostrom.server.kernel.auth.OwnedDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class DeviceDaemonsTest {
    private val now = Instant.parse("2026-11-15T12:00:00Z")

    @Test
    fun aDaemonIsOnlineForAMinuteAfterItsLastPoll() {
        val recent = DaemonPresence.of(now - 59.seconds, "1.0.0", now, listOf("resume.v1"))
        assertEquals(DaemonPresence(true, now - 59.seconds, "1.0.0", listOf("resume.v1")), recent)
        assertEquals(false, DaemonPresence.of(now - 60.seconds, null, now)?.online)
        assertEquals(emptyList(), DaemonPresence.of(now, null, now)?.features)
        assertNull(DaemonPresence.of(null, "1.0.0", now))
    }

    @Test
    fun aRecordedPollIsRewrittenEveryFiveSecondsAtMost() {
        assertEquals(listOf(60.seconds, 5.seconds), listOf(DaemonPresence.ONLINE_TTL, DaemonPresence.TOUCH))
    }

    @Test
    fun anOwnedDeviceKeepsItsDaemonAndFolders() {
        val id = Uuid.random()
        val folder = DaemonFolder("f1", "ostrom")
        val device = OwnedDevice(id, "Work laptop", null, listOf(folder))
        assertEquals(
            listOf(id, "Work laptop", null, listOf(folder)),
            with(device) { listOf(this.id, name, daemon, folders) },
        )
        assertEquals(listOf("f1", "ostrom", null), listOf(folder.id, folder.name, folder.label))
        val request = DaemonListingRequest(id, "f1", 20, null)
        assertEquals(listOf(id, "f1", 20, null), with(request) { listOf(requestId, folderId, limit, before) })
    }
}
