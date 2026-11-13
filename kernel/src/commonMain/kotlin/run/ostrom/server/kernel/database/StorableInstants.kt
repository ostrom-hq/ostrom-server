// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import kotlin.time.Instant

// Years 1 to 9999: every time the server writes, and well inside what a timestamptz holds (4713 BC to 294276 AD).
private val EARLIEST = Instant.parse("0001-01-01T00:00:00Z")
private val LATEST = Instant.parse("9999-12-31T23:59:59.999999999Z")

/**
 * Reads an ISO 8601 instant a query may bind. `kotlin.time` reads years far beyond what PostgreSQL stores: bound to
 * such a year, the query fails and the request would answer 500 instead of the 400 of a malformed parameter.
 *
 * @param text What the caller sent, a page cursor's time for instance.
 * @return The instant, or null when the text is not one or its year is outside 1 to 9999.
 */
fun storableInstantOrNull(text: String): Instant? = Instant.parseOrNull(text)?.takeIf { it in EARLIEST..LATEST }
