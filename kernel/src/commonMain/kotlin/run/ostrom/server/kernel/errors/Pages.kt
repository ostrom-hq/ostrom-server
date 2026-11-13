// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.errors

/**
 * A page a list refuses: a size of 0 or less, or a cursor no page of this organization gave (garbage, forged, or
 * another organization's). A 400, never an empty page that hides the mistake.
 *
 * @param message What is wrong, safe to show.
 */
class InvalidPageException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/** The rules every list shares for its page (SRV-LECTURES, `ListBoundsPostgresTest`). */
object Pages {
    /**
     * The size of a page: the one asked, brought down to [maximum]; [default] when none is asked.
     *
     * @param requested The `limit` asked, or null.
     * @param default The size without one.
     * @param maximum The largest page.
     * @return The size, 1 to [maximum].
     * @throws InvalidPageException When [requested] is 0 or less.
     */
    fun size(requested: Int?, default: Int, maximum: Int): Int = when {
        requested == null -> default
        requested < 1 -> throw InvalidPageException("limit is 1 to $maximum.")
        else -> requested.coerceAtMost(maximum)
    }

    /**
     * A page read after a cursor, refused when the cursor names nothing of this list: an unknown cursor reads no row,
     * so an empty page is the one case to check, by [exists] (one read by key, only then).
     *
     * @param name The cursor's parameter, `after` or `before`.
     * @param cursor The cursor, or null for the first page.
     * @param page The page read.
     * @param exists Whether the cursor names a row of this list, in this organization.
     * @return [page].
     * @throws InvalidPageException When the page is empty and the cursor names no row.
     */
    suspend fun <C : Any, T> checked(name: String, cursor: C?, page: List<T>, exists: suspend (C) -> Boolean): List<T> {
        if (cursor != null && page.isEmpty() && !exists(cursor)) unknownCursor(name)
        return page
    }

    /**
     * Refuses a cursor that names nothing this organization's pages give.
     *
     * @param name The cursor's parameter, `after` or `before`.
     * @throws InvalidPageException Always.
     */
    fun unknownCursor(name: String): Nothing =
        throw InvalidPageException("$name is not the next of a page of this list.")
}
