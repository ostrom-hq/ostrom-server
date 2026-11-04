// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates

import dev.ostrom.server.gates.presentation.GateRequest
import dev.ostrom.server.kernel.http.OstromJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A gate answers its `gate` as `null` when no workflow gate matched: required and nullable in `openapi.yaml`. */
class ContractNullsTest {

    @Test
    fun aGateOutsideEveryWorkflowWritesItsGateNullAndLeavesTheOptionalFieldsOut() {
        val request = GateRequest(
            id = "g1",
            dev = "ada",
            cls = "push",
            label = "Push",
            tool = "Bash",
            command = "git push",
            key = "k",
            gate = null,
            mode = "enforce",
            status = "pending",
            at = "2026-11-02",
        )
        val written = OstromJson.encodeToJsonElement(GateRequest.serializer(), request).jsonObject
        assertEquals(JsonNull, written["gate"])
        assertFalse("approvalId" in written, "Optional: absent when null")
    }
}
