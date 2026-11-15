// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.InvalidOnBehalfException
import run.ostrom.server.runners.presentation.onBehalfOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** `Ostrom-On-Behalf-Of`'s values (TeamCode's `onBehalfOf()`): at most one, a member id or a login. */
class OnBehalfHeaderTest {
    @Test
    fun oneValidValueNamesAMemberAndAnythingElseIsRefused() {
        assertNull(onBehalfOf(null))
        assertEquals("bob", onBehalfOf(listOf("bob")))
        val id = "00000000-0000-0000-0000-0000000000b2"
        assertEquals(id, onBehalfOf(listOf(id)))
        for (values in listOf(listOf("bob", "bob"), emptyList(), listOf("a b"), listOf(""), listOf("x".repeat(65)))) {
            assertFailsWith<InvalidOnBehalfException>(values.toString()) { onBehalfOf(values) }
        }
    }
}
