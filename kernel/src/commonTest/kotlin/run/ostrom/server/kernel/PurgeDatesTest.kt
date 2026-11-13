// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import run.ostrom.server.kernel.database.PurgeDateRefusedException
import run.ostrom.server.kernel.database.PurgeDates
import run.ostrom.server.kernel.errors.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** A purge date lies between 1970 and now; any other is refused as a bad request. */
class PurgeDatesTest {
    private val now = Instant.parse("2026-11-03T20:00:00Z")

    @Test
    fun aDateBetweenNineteenSeventyAndNowIsAccepted() {
        for (before in listOf(now, now - 7.days, Instant.fromEpochSeconds(0))) PurgeDates.check(before, now)
    }

    @Test
    fun aFutureATooDistantOrAnUnstorableDateIsRefusedAsABadRequest() {
        val refused = listOf(
            now + 1.milliseconds,
            Instant.DISTANT_FUTURE,
            Instant.fromEpochSeconds(0) - 1.milliseconds,
            Instant.DISTANT_PAST,
        )
        for (before in refused) {
            val error = assertFailsWith<PurgeDateRefusedException>("$before") { PurgeDates.check(before, now) }
            assertEquals(ErrorKind.INVALID_REQUEST, error.kind)
        }
    }
}
