// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets

import run.ostrom.server.secrets.domain.LaunchCaps
import kotlin.test.Test
import kotlin.test.assertEquals

/** A launch's caps (`launches.limits.v1`): any one of them is a limit, zero is none. */
class LaunchCapsTest {
    @Test
    fun aCapAppliesWhenAnyOfItsThreeLimitsIsSet() {
        assertEquals(false, LaunchCaps().any)
        assertEquals(true, LaunchCaps(budgetMicros = 1).any)
        assertEquals(true, LaunchCaps(maxConcurrent = 1).any)
        assertEquals(true, LaunchCaps(paused = true).any)
    }
}
