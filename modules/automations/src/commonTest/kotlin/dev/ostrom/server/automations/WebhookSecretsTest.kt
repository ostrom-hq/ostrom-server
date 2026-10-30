// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations

import dev.ostrom.server.automations.infrastructure.WebhookSecretsImpl
import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class WebhookSecretsTest {
    @Test
    fun theSignatureIsHmacSha256() = runTest {
        val secrets = WebhookSecretsImpl(Ed25519InstanceSigner.generate())
        // RFC 4231, test cases 1 and 2.
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            secrets.sign("\u000b".repeat(20), "Hi There".encodeToByteArray()).toHexString(),
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            secrets.sign("Jefe", "what do ya want for nothing?".encodeToByteArray()).toHexString(),
        )
        // A key longer than a block is hashed first (RFC 2104).
        val long = "k".repeat(100)
        assertEquals(64, secrets.sign(long, byteArrayOf()).toHexString().length)
        assertNotEquals(
            secrets.sign(long, byteArrayOf()).toHexString(),
            secrets.sign("k".repeat(64), byteArrayOf()).toHexString(),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            secrets.digest("abc".encodeToByteArray()),
        )
    }

    @Test
    fun aSecretIsDerivedAgainFromTheInstanceKeyAndItsGeneration() = runTest {
        val signer = Ed25519InstanceSigner.generate()
        val secrets = WebhookSecretsImpl(signer)
        val id = Uuid.random()
        val first = secrets.secret(id, 1)
        assertTrue(first.startsWith("whsec_"))
        assertEquals(6 + 48, first.length)
        assertEquals(first, WebhookSecretsImpl(signer).secret(id, 1))
        assertNotEquals(first, secrets.secret(id, 2))
        assertNotEquals(first, secrets.secret(Uuid.random(), 1))
        assertNotEquals(first, WebhookSecretsImpl(Ed25519InstanceSigner.generate()).secret(id, 1))
    }
}
