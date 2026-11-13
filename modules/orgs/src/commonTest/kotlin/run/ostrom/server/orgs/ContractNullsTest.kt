// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs

import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.orgs.presentation.InvitationLinkResponse
import run.ostrom.server.orgs.presentation.InvitationPreviewResponse
import run.ostrom.server.orgs.presentation.InvitationResponse
import run.ostrom.server.orgs.presentation.MemberResponse
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The organization answers write the fields `openapi.yaml` declares required and nullable as `null` (never absent). */
class ContractNullsTest {

    private fun JsonObject.nulls(vararg keys: String) = keys.forEach { assertEquals(JsonNull, this[it], it) }

    @Test
    fun aMemberWithoutAnAddressWritesItsEmailNull() {
        val member = MemberResponse("m1", "ada", "Ada", "", null, "member", "2026-11-02")
        OstromJson.encodeToJsonElement(MemberResponse.serializer(), member).jsonObject.nulls("email")
    }

    @Test
    fun aLinkAndItsPreviewWriteTheirNulls() {
        val invitation = InvitationResponse("i1", null, "member", "2026-11-02", "2026-11-09", null, 0)
        val answer = InvitationLinkResponse(invitation, "https://app.ostrom.run/invite/x")
        val link = OstromJson.encodeToJsonElement(InvitationLinkResponse.serializer(), answer)
        link.jsonObject.getValue("invitation").jsonObject.nulls("email", "maxUses")
        val preview = InvitationPreviewResponse("Acme", "acme", "member", null, "2026-11-09")
        OstromJson.encodeToJsonElement(InvitationPreviewResponse.serializer(), preview).jsonObject.nulls("email")
    }
}
