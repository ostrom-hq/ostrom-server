// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.receipts

import run.ostrom.core.receipts.CanonicalJson
import run.ostrom.server.kernel.signing.Ed25519InstanceSigner
import run.ostrom.server.receipts.domain.models.ReceiptKey
import run.ostrom.server.receipts.domain.usecases.VerifyReceiptUseCaseImpl
import run.ostrom.server.receipts.infrastructure.InstanceReceiptSigner
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
        val verify = VerifyReceiptUseCaseImpl(access(FakeReceipts()), keys)

        val outcome = verify.verify(ORG_A, signed)
        assertTrue(outcome.ok)
        assertFalse(outcome.orgBound, "A body that names no organization is never bound to one")
        assertEquals(instance.keyId, outcome.kid)
        assertNull(outcome.reason)

        val tampered = verify.verify(ORG_A, JsonObject(signed + ("initiator" to JsonPrimitive("mallory"))))
        assertFalse(tampered.ok)
        assertNull(tampered.kid)
        assertNotNull(tampered.reason)

        keys.keys = listOf(signer.receiptKey())
        assertFalse(verify.verify(ORG_A, signed).ok, "Only the instance's keys are trusted")
    }

    @Test
    fun aSignedBodyIsNeverSignedAgain() = runTest {
        assertFailsWith<IllegalArgumentException> {
            InstanceReceiptSigner(signer).sign(buildJsonObject { put("signature", "x") })
        }
    }
}
