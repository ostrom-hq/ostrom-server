// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.database.storableInstantOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** An instant a query may bind: years 1 to 9999, nothing PostgreSQL would refuse. */
class StorableInstantsTest {
    @Test
    fun yearsOneToNineThousandNineHundredNinetyNineAreRead() {
        for (text in listOf("0001-01-01T00:00:00Z", "2026-11-03T13:40:00.123456Z", "9999-12-31T23:59:59.999999999Z")) {
            assertEquals(Instant.parse(text), storableInstantOrNull(text), text)
        }
    }

    @Test
    fun anythingElseIsNot() {
        val beyond = listOf("+300000-01-01T00:00:00Z", "-300000-01-01T00:00:00Z", "+10000-01-01T00:00:00Z")
        for (text in listOf("yesterday", "") + beyond) {
            assertNull(storableInstantOrNull(text), text)
        }
    }
}
