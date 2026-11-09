// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners

import run.ostrom.server.runners.infrastructure.textAt
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonPathsTest {
    private val answer = Json.parseToJsonElement("""{"metadata":{"resourceVersion":"7","labels":["a"]},"kind":"Pod"}""")

    @Test
    fun aPathReadsTheValueAtItsEnd() {
        assertEquals("7", answer.textAt("metadata", "resourceVersion"))
        assertEquals("Pod", answer.textAt("kind"))
    }

    @Test
    fun aMissingKeyAStepThatIsNoObjectOrAnEndThatIsNoValueReadsAsNull() {
        assertNull(answer.textAt("metadata", "uid"))
        assertNull(answer.textAt("kind", "name"))
        assertNull(answer.textAt("metadata", "labels"))
    }
}
