// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity

import run.ostrom.server.identity.infrastructure.IdentitySetupLinks
import run.ostrom.server.identity.infrastructure.present
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The reads of the rows of the identity tables. */
class IdentityRowsTest {
    @Test
    fun aRowMissingAColumnWrittenWithAnotherFailsAtTheReadWithTheColumnNamed() {
        val t = IdentitySetupLinks
        val malformed = ResultRow.createAndFillValues(mapOf(t.usedBy to null, t.usedAt to null))
        val error = assertFailsWith<IllegalStateException> { malformed.present(t.usedBy, t.usedAt) }
        assertEquals("used_by is set with used_at", error.message)
    }
}
