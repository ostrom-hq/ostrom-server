// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.InvalidPageException
import run.ostrom.server.kernel.errors.Pages
import kotlinx.coroutines.test.runTest
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
    fun aPageAfterACursorIsCheckedOnlyWhenItIsEmpty() = runTest {
        var reads = 0
        val known: suspend (String) -> Boolean = { reads++; it == "x" }
        val unknown: suspend (String) -> Boolean = { reads++; false }
        assertEquals(emptyList(), Pages.checked<String, Int>("after", null, emptyList(), unknown), "The first page")
        assertEquals(listOf(1), Pages.checked("after", "x", listOf(1), unknown), "A page that follows its cursor")
        assertEquals(0, reads, "No read when the page has rows or no cursor")
        assertEquals(emptyList(), Pages.checked("after", "x", emptyList<Int>(), known), "The end of the list")
        assertFailsWith<InvalidPageException> { Pages.checked("before", "x", emptyList<Int>(), unknown) }
        assertEquals(2, reads)
    }

    @Test
    fun anUnknownCursorIsRefused() {
        val refused = assertFailsWith<InvalidPageException> { Pages.unknownCursor("before") }
        assertEquals("before is not the next of a page of this list.", refused.message)
    }
}
