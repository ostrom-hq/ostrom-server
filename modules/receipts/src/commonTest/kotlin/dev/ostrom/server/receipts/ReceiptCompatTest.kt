// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.receipts

import dev.ostrom.core.receipts.CanonicalJson
import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import dev.ostrom.server.receipts.domain.models.ReceiptKey
import dev.ostrom.server.receipts.domain.usecases.VerifyReceiptUseCaseImpl
import dev.ostrom.server.receipts.infrastructure.InstanceReceiptSigner
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiptCompatTest {
    private val signer = SeedSigner(hex(GoldenVectors.SEED_HEX))

    @Test
    fun theTestKeyHasThePrototypesKeyId() {
        assertEquals(GoldenVectors.KID, signer.keyId)
    }

    @Test
    fun everyGoldenReceiptIsSignedToTheSameBytes() = runTest {
        assertEquals(4, GoldenVectors.receipts.size)
        for ((name, text, canonical) in GoldenVectors.receipts) {
            val receipt = Json.parseToJsonElement(text).jsonObject
            val body = JsonObject(receipt - "signature")
            assertEquals(canonical, CanonicalJson.encode(body), "$name: canonical form")
            val signed = InstanceReceiptSigner(signer).sign(body)
            assertEquals(receipt.text("signature"), signed.text("signature"), "$name: signature field")
            assertEquals(body, JsonObject(signed - "signature"), "$name: nothing else added")
        }
    }

    @Test
    fun aReceiptOfTheInstanceKeyVerifiesWithTheVerifierOfCore() = runTest {
        val instance = Ed25519InstanceSigner.generate()
        val body = JsonObject(draft() + mapOf("id" to JsonPrimitive("rcpt_1"), "createdAt" to JsonPrimitive("now")))
        val signed = InstanceReceiptSigner(instance).sign(body)
        val keys = FakeKeys(listOf(ReceiptKey(instance.keyId, instance.algorithm, instance.publicKeyPem)))
        val verify = VerifyReceiptUseCaseImpl(FakeReceipts(), keys)

        val outcome = verify.verify(signed)
        assertTrue(outcome.ok)
        assertEquals(instance.keyId, outcome.kid)
        assertNull(outcome.reason)

        val tampered = verify.verify(JsonObject(signed + ("initiator" to JsonPrimitive("mallory"))))
        assertFalse(tampered.ok)
        assertNull(tampered.kid)
        assertNotNull(tampered.reason)

        keys.keys = listOf(signer.receiptKey())
        assertFalse(verify.verify(signed).ok, "Only the instance's keys are trusted")
    }

    @Test
    fun aSignedBodyIsNeverSignedAgain() = runTest {
        assertFailsWith<IllegalArgumentException> {
            InstanceReceiptSigner(signer).sign(buildJsonObject { put("signature", "x") })
        }
    }
}
