// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage

import run.ostrom.server.usage.infrastructure.DateColumnType
import run.ostrom.server.usage.infrastructure.FirstWholeDay
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The pieces the ledger's statements are built of, apart from the database. */
class UsageExpressionsTest {
    @Test
    fun theFirstWholeDayIsTheMidnightAtOrAfterTheStartToTheMicrosecond() {
        val cases = mapOf(
            "2026-11-01T00:00:00Z" to "2026-11-01",
            "2026-11-01T00:00:00.000001Z" to "2026-11-02",
            "2026-11-01T13:45:00Z" to "2026-11-02",
            // PostgreSQL keeps microseconds: within half of one of a midnight is that midnight, as the driver rounds.
            "2026-11-01T23:59:59.9999996Z" to "2026-11-02",
            "2026-11-01T00:00:00.0000004Z" to "2026-11-01",
        )
        for ((since, day) in cases) {
            val first = FirstWholeDay(Instant.parse(since))
            assertEquals(LocalDate.parse(day), first.day, since)
            assertEquals(Instant.parse("${day}T00:00:00Z"), first.at, since)
        }
    }

    @Test
    fun aDateReadsBackFromTheDriver() {
        val date = DateColumnType()
        assertEquals("DATE", date.sqlType())
        assertEquals(LocalDate.of(2026, 11, 1), date.valueFromDB(java.sql.Date.valueOf("2026-11-01")))
        assertEquals(LocalDate.of(2026, 11, 1), date.valueFromDB(LocalDate.of(2026, 11, 1)))
    }
}
