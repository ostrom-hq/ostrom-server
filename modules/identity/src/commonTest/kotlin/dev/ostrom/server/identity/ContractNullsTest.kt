// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity

import dev.ostrom.server.identity.presentation.AuditEventResponse
import dev.ostrom.server.identity.presentation.AuditEventsResponse
import dev.ostrom.server.identity.presentation.TokenResponse
import dev.ostrom.server.kernel.http.OstromJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The identity answers write the fields `openapi.yaml` declares required and nullable as `null` (never absent). */
class ContractNullsTest {

    private fun JsonObject.nulls(vararg keys: String) = keys.forEach { assertEquals(JsonNull, this[it], it) }

    @Test
    fun aTokenThatNeverExpiresNorServedWritesBothNulls() {
        val scopes = listOf("sessions:read")
        val token = TokenResponse("t1", "ci", "personal", scopes, "ost_…1234", "2026-11-02", null, null)
        OstromJson.encodeToJsonElement(TokenResponse.serializer(), token).jsonObject.nulls("expiresAt", "lastUsedAt")
    }

    @Test
    fun anAuditEventInItsLastPageWritesItsNulls() {
        val event = AuditEventResponse(
            "e1", 1, null, null, "login", "user", "u1", emptyMap(), "2026-11-02", null, "h", "s", "k",
        )
        val last = AuditEventsResponse(listOf(event), null)
        val page = OstromJson.encodeToJsonElement(AuditEventsResponse.serializer(), last)
        page.jsonObject.nulls("nextBefore")
        page.jsonObject.getValue("events").jsonArray.single().jsonObject.nulls("orgId", "actorId", "previousHash")
    }
}
