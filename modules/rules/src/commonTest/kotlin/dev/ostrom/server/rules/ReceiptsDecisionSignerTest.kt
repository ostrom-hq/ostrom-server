// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.core.receipts.Digests
import dev.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import dev.ostrom.server.rules.domain.models.Decision
import dev.ostrom.server.rules.domain.models.DecisionSubject
import dev.ostrom.server.rules.fakes.RulesFixtures
import dev.ostrom.server.rules.infrastructure.ReceiptsDecisionSigner
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ReceiptsDecisionSignerTest {
    private val drafts = mutableListOf<Triple<Uuid, JsonObject, String?>>()
    private val signer = ReceiptsDecisionSigner(
        object : SignReceiptUseCase {
            override suspend fun invoke(orgId: Uuid, draft: JsonObject, id: String?): JsonObject {
                drafts += Triple(orgId, draft, id)
                return draft
            }
        },
    )
    private val after = buildJsonObject { put("status", "accepted") }

    @Test
    fun aRuleChangeNamesTheRuleAndItsDigestAsThePrototypeDoes() = runTest {
        val decision = Decision(
            orgId = RulesFixtures.orgA,
            id = "dec_1",
            at = RulesFixtures.epoch,
            by = "lyes",
            subject = DecisionSubject("rule", "rule_1", "Main goes through a PR"),
            action = "accepted",
            before = null,
            after = after,
            ruleDigest = "sha256:abc",
        )
        signer.sign(decision)
        val (orgId, draft, id) = drafts.single()
        assertEquals(RulesFixtures.orgA, orgId)
        assertEquals("rcpt_dec_1", id)
        val preimage = buildJsonObject {
            put("ruleId", "rule_1")
            put("action", "accepted")
            put("before", JsonNull)
            put("after", after)
        }
        assertEquals(
            Json.parseToJsonElement(
                """{"subject":{"kind":"rule-change","id":"rule_1","digest":"${Digests.digestOf(preimage)}"},""" +
                    """"initiator":"lyes","rules":[{"id":"rule_1","digest":"sha256:abc"}],""" +
                    """"decisions":[{"approvalId":"dec_1","by":"lyes",""" +
                    """"at":"2026-10-30T12:00:00.000Z","result":"accepted"}]}""",
            ),
            draft,
        )
        assertEquals(Digests.digestOf(after), signer.digestOf(after))
    }

    @Test
    fun aPolicyChangeIsAPolicyReceipt() = runTest {
        val before = buildJsonObject { put("selfApproval", "sole-approver") }
        val now = buildJsonObject { put("selfApproval", "never") }
        signer.sign(
            Decision(
                orgId = RulesFixtures.orgA,
                id = "dec_2",
                at = RulesFixtures.epoch,
                by = "lyes",
                subject = DecisionSubject("policy", "self-approval", "Self-approval"),
                action = "edited",
                before = before,
                after = now,
            ),
        )
        val draft = drafts.single().second
        val preimage = buildJsonObject {
            put("subject", "self-approval")
            put("action", "edited")
            put("before", before)
            put("after", now)
        }
        assertEquals(
            Json.parseToJsonElement(
                """{"kind":"policy","id":"self-approval","digest":"${Digests.digestOf(preimage)}"}""",
            ),
            draft.getValue("subject"),
        )
        assertEquals(Json.parseToJsonElement("[]"), draft.getValue("rules"))
    }
}
