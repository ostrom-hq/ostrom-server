// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.mcphost

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonAccessTest {
    private val json = Json.parseToJsonElement("""{"s":"x","n":5,"f":1.5,"o":{},"a":[]}""").jsonObject

    @Test
    fun membersAreReadOnlyWithTheirType() {
        assertEquals("x", json.text("s"))
        assertNull(json.text("n"))
        assertNull(json.text("o"))
        assertNull(json.text("missing"))
        assertEquals(5L, json.number("n"))
        assertNull(json.number("s"))
        assertNull(json.number("f"))
        assertNull(json.number("a"))
        assertEquals(0, json.obj("o")!!.size)
        assertNull(json.obj("a"))
    }
}
