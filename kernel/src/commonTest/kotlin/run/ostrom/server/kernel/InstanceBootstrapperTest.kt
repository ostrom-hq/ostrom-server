// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.module.SetupLinkOutput
import kotlin.test.Test
import kotlin.test.assertEquals

class InstanceBootstrapperTest {
    @Test
    fun aSetupLinkCarriesItsUrlAndExpiry() {
        val link = SetupLinkOutput("https://app.example.com/setup#token=osb_abc", "2026-11-13T11:00:00Z")

        assertEquals("https://app.example.com/setup#token=osb_abc", link.url)
        assertEquals("2026-11-13T11:00:00Z", link.expiresAt)
    }
}
