// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.receipts

import dev.ostrom.server.kernel.errors.InvalidPageException
import dev.ostrom.server.receipts.domain.models.InvalidReceiptException
import dev.ostrom.server.receipts.domain.models.ReceiptNotFoundException
import dev.ostrom.server.receipts.domain.models.ReceiptQuery
import dev.ostrom.server.receipts.domain.services.ReceiptBodySigner
import dev.ostrom.server.receipts.domain.usecases.GetReceiptUseCaseImpl
import dev.ostrom.server.receipts.domain.usecases.ListReceiptKeysUseCaseImpl
import dev.ostrom.server.receipts.domain.usecases.ListReceiptsUseCaseImpl
import dev.ostrom.server.receipts.domain.usecases.SignReceiptUseCaseImpl
import dev.ostrom.server.receipts.domain.usecases.VerifyReceiptUseCaseImpl
import dev.ostrom.server.receipts.domain.usecases.isoMillis
import dev.ostrom.server.receipts.infrastructure.InstanceReceiptSigner
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class ReceiptsUseCasesTest {
    private val receipts = FakeReceipts()
    private val instance = SeedSigner(hex(GoldenVectors.SEED_HEX))
    private var signed = 0
    private val signer = object : ReceiptBodySigner {
        override suspend fun sign(body: JsonObject): JsonObject {
            signed++
            return InstanceReceiptSigner(instance).sign(body)
        }
    }
    private val sign = SignReceiptUseCaseImpl(receipts, signer, FixedClock())

    @Test
    fun theServerSetsTheIdTheDateAndTheSignature() = runTest {
        val receipt = sign(ORG_A, draft())
        assertTrue(Regex("rcpt_[0-9a-f-]{36}").matches(receipt.text("id")))
        assertEquals("2026-10-30T09:12:34.567Z", receipt.text("createdAt"))
        assertTrue(receipt.text("signature").startsWith("ed25519;kid=${GoldenVectors.KID};sha256="))
        val stored = receipts.rows.single()
        assertEquals(ORG_A, stored.orgId)
        assertEquals("gate" to "gate-1", stored.subjectKind to stored.subjectId)
        assertEquals(receipt, stored.body)
    }

    @Test
    fun aReceiptWithAnIdIsSignedOnce() = runTest {
        val first = sign(ORG_A, draft(), "rcpt_gate-1")
        val again = sign(ORG_A, draft(subjectId = "other"), "rcpt_gate-1")
        assertEquals(first, again)
        assertEquals(1, signed)
        assertEquals("rcpt_gate-1", first.text("id"))
        sign(ORG_B, draft(), "rcpt_gate-1")
        assertEquals(2, receipts.rows.size, "The same id in another organization is another receipt")
    }

    @Test
    fun aMalformedDraftIsRefused() = runTest {
        val noSubject = JsonObject(draft() - "subject")
        val numericKind = JsonObject(
            draft() + ("subject" to buildJsonObject { put("kind", 1); put("id", "x") }),
        )
        val emptyId = JsonObject(draft() + ("subject" to buildJsonObject { put("kind", "gate"); put("id", "") }))
        val noId = JsonObject(draft() + ("subject" to buildJsonObject { put("kind", "gate") }))
        val subjectText = JsonObject(draft() + ("subject" to JsonPrimitive("gate")))
        for (bad in listOf(noSubject, numericKind, emptyId, noId, subjectText)) {
            assertFailsWith<InvalidReceiptException> { sign(ORG_A, bad) }
        }
        for (field in listOf("id", "createdAt", "signature")) {
            val withField = JsonObject(draft() + (field to JsonPrimitive("x")))
            val refused = assertFailsWith<InvalidReceiptException> { sign(ORG_A, withField) }
            assertEquals("The server sets a receipt's id, createdAt and signature.", refused.message)
        }
        val withOrg = JsonObject(draft() + ("orgId" to JsonPrimitive(ORG_B.toString())))
        assertEquals(
            "The server sets a receipt's orgId.",
            assertFailsWith<InvalidReceiptException> { sign(ORG_A, withOrg) }.message,
        )
        for (field in listOf("launchId", "sessionId")) {
            for (bad in listOf(JsonPrimitive(""), JsonPrimitive(1))) {
                val refused = assertFailsWith<InvalidReceiptException> {
                    sign(ORG_A, JsonObject(draft() + (field to bad)))
                }
                assertEquals("A receipt's launchId and sessionId are non-empty texts.", refused.message)
            }
        }
        for (id in listOf("gate-1", "rcpt_", "rcpt_a b", "rcpt_" + "x".repeat(121))) {
            assertFailsWith<InvalidReceiptException> { sign(ORG_A, draft(), id) }
        }
        assertEquals(0, signed)
    }

    @Test
    fun datesAreWrittenAsThePrototypeWritesThem() {
        assertEquals("2026-10-30T09:12:34.000Z", Instant.parse("2026-10-30T09:12:34Z").isoMillis())
        assertEquals("2026-10-30T09:12:34.500Z", Instant.parse("2026-10-30T09:12:34.5Z").isoMillis())
        assertEquals("2026-10-30T09:12:34.567Z", Instant.parse("2026-10-30T09:12:34.567891Z").isoMillis())
    }

    @Test
    fun receiptsAreReadInTheirOrganizationOnly() = runTest {
        val receipt = sign(ORG_A, draft())
        val get = GetReceiptUseCaseImpl(receipts)
        assertEquals(receipt, get(ORG_A, receipt.text("id")))
        assertFailsWith<ReceiptNotFoundException> { get(ORG_B, receipt.text("id")) }

        val list = ListReceiptsUseCaseImpl(receipts)
        assertEquals(listOf(receipt), list(ORG_A, ReceiptQuery(limit = 0)).map { it.body })

        assertEquals(1, receipts.lastQuery?.limit)
        list(ORG_A, ReceiptQuery(subjectKind = "gate", limit = 1_000))
        assertEquals(ReceiptQuery.MAX_PAGE, receipts.lastQuery?.limit)
        assertEquals("gate", receipts.lastQuery?.subjectKind)
        assertEquals(emptyList(), list(ORG_B, ReceiptQuery()))
        // After its cursor, none after the last; a receipt the organization does not hold is a 400.
        val id = receipt.text("id")
        assertEquals(emptyList(), list(ORG_A, ReceiptQuery(before = id)))
        assertFailsWith<InvalidPageException> { list(ORG_A, ReceiptQuery(before = "nope")) }
        assertFailsWith<InvalidPageException> { list(ORG_B, ReceiptQuery(before = id)) }
    }

    @Test
    fun aStoredReceiptIsVerifiedWithTheInstanceKeys() = runTest {
        val receipt = sign(ORG_A, draft())
        val keys = FakeKeys(listOf(instance.receiptKey()))
        val verify = VerifyReceiptUseCaseImpl(receipts, keys)
        val outcome = verify(ORG_A, receipt.text("id"))
        assertTrue(outcome.ok)
        assertTrue(outcome.orgBound, "It names A, who verifies it")
        assertEquals(GoldenVectors.KID, outcome.kid)
        assertFailsWith<ReceiptNotFoundException> { verify(ORG_B, receipt.text("id")) }
        assertEquals(listOf(instance.receiptKey()), ListReceiptKeysUseCaseImpl(keys)())
    }

    @Test
    fun theSubjectIsReadFromItsObject() = runTest {
        val receipt = sign(ORG_A, buildJsonObject {
            putJsonObject("subject") {
                put("kind", "session")
                put("id", "sess-1~r2")
            }
            put("initiator", "dev-a")
        })
        assertEquals("session" to "sess-1~r2", receipts.rows.single().let { it.subjectKind to it.subjectId })
        assertEquals("dev-a", receipt.text("initiator"))
    }
}
