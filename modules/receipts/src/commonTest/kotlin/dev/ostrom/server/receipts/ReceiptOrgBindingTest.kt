// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.receipts

import dev.ostrom.server.receipts.domain.models.StoredReceipt
import dev.ostrom.server.receipts.domain.usecases.SignReceiptUseCaseImpl
import dev.ostrom.server.receipts.domain.usecases.VerifyReceiptUseCaseImpl
import dev.ostrom.server.receipts.infrastructure.InstanceReceiptSigner
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// The organization is inside the signed body: the instance key is the same for every organization, so without it
// two organizations with the same sources in the same minute signed the same bytes, and one's receipt passed for the
// other's.
class ReceiptOrgBindingTest {
    private val receipts = FakeReceipts()
    private val instance = SeedSigner(hex(GoldenVectors.SEED_HEX))
    private val clock = FixedClock()
    private val sign = SignReceiptUseCaseImpl(receipts, InstanceReceiptSigner(instance), clock)
    private val verify = VerifyReceiptUseCaseImpl(receipts, FakeKeys(listOf(instance.receiptKey())))

    @Test
    fun twoOrganizationsWithTheSameSourcesInTheSameMinuteSignDifferentBodies() = runTest {
        val ofA = sign(ORG_A, draft("harness"), HARNESS)
        val ofB = sign(ORG_B, draft("harness"), HARNESS)
        assertNotEquals(JsonObject(ofA - "signature"), JsonObject(ofB - "signature"), "The signed bodies differ")
        assertNotEquals(ofA.text("signature"), ofB.text("signature"), "So do the signatures")
        assertEquals(ORG_A.toString(), (ofA["orgId"] as? JsonPrimitive)?.content, "A's receipt names A")
    }

    @Test
    fun aReceiptOfOneOrganizationNeverVerifiesAsAnothers() = runTest {
        val ofA = sign(ORG_A, draft("harness"), HARNESS)
        // A's receipt, byte for byte, presented to B: B's store holds it under the same id.
        receipts.rows += StoredReceipt(ORG_B, HARNESS, "gate", "harness", ofA, clock.now)

        val asB = verify(ORG_B, HARNESS)
        assertFalse(asB.ok, "A's receipt is not B's")
        assertEquals("This receipt was signed for another organization.", asB.reason)
        assertTrue(verify(ORG_A, HARNESS).ok, "It stays A's")
    }

    @Test
    fun theDraftNamesItsLaunchAndSessionInsideTheSignedBody() = runTest {
        val bound = mapOf("launchId" to str("lch_1"), "sessionId" to str("s-1"))
        val draft = JsonObject(draft("s-1", "session") + bound)
        val receipt = sign(ORG_A, draft)
        assertEquals("lch_1" to "s-1", receipt.text("launchId") to receipt.text("sessionId"))
        assertTrue(verify(ORG_A, receipt.text("id")).orgBound)
        val moved = JsonObject(receipt + ("sessionId" to str("s-2")))
        assertFalse(verify.verify(ORG_A, moved).ok, "Signed: another session breaks the signature")
        assertFalse(verify.verify(ORG_A, moved).orgBound, "A receipt that does not verify is bound to nothing")
    }

    @Test
    fun anOrganizationThatIsNotATextIsAnotherOrganization() = runTest {
        val fields = mapOf("orgId" to JsonPrimitive(1), "id" to str("rcpt_1"), "createdAt" to str("x"))
        val body = JsonObject(draft() + fields)
        val outcome = verify.verify(ORG_A, InstanceReceiptSigner(instance).sign(body))
        assertFalse(outcome.ok)
        assertFalse(outcome.orgBound)
        assertEquals("This receipt was signed for another organization.", outcome.reason)
    }

    @Test
    fun aReceiptSignedBeforeTheOrganizationVerifiesUnbound() = runTest {
        val body = JsonObject(draft() + mapOf("id" to str("rcpt_old"), "createdAt" to str("x")))
        val outcome = verify.verify(ORG_B, InstanceReceiptSigner(instance).sign(body))
        assertTrue(outcome.ok, "Its signature still holds")
        assertFalse(outcome.orgBound, "Never shown as bound")
        assertEquals(null, outcome.reason)
    }

    private fun str(value: String) = JsonPrimitive(value)

    private companion object {
        const val HARNESS = "rcpt_harness_1.0.0"
    }
}
