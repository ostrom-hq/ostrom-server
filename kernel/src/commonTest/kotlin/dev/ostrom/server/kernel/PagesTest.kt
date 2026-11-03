// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.InvalidPageException
import dev.ostrom.server.kernel.errors.Pages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [Pages]: a page size is brought down to its maximum, never up from 0; an unknown cursor is a 400. */
class PagesTest {
    @Test
    fun aSizeIsTheOneAskedUpToTheMaximumAndTheDefaultWithoutOne() {
        assertEquals(50, Pages.size(null, 50, 100))
        assertEquals(1, Pages.size(1, 50, 100))
        assertEquals(100, Pages.size(100, 50, 100))
        assertEquals(100, Pages.size(100_000, 50, 100))
    }

    @Test
    fun aSizeOfZeroOrLessIsRefused() {
        for (size in listOf(0, -1, Int.MIN_VALUE)) {
            val refused = assertFailsWith<InvalidPageException> { Pages.size(size, 50, 100) }
            assertEquals(ErrorKind.INVALID_REQUEST, refused.kind)
            assertEquals("limit is 1 to 100.", refused.message)
        }
    }

    @Test
    fun anUnknownCursorIsRefused() {
        val refused = assertFailsWith<InvalidPageException> { Pages.unknownCursor("before") }
        assertEquals("before is not the next of a page of this list.", refused.message)
    }
}
