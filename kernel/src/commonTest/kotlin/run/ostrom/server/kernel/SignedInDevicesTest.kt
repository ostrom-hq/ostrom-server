// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.auth.SignedInDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class SignedInDevicesTest {
    @Test
    fun aSignedInDeviceKeepsWhatItsIdentityKnows() {
        val id = Uuid.random()
        val user = Uuid.random()
        val device = SignedInDevice(id, user, "Work laptop", "macos", null)
        assertEquals(
            listOf(id, user, "Work laptop", "macos", null),
            listOf(device.id, device.userId, device.name, device.platform, device.osVersion),
        )
    }
}
