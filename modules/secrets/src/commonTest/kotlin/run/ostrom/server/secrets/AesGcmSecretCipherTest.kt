// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.secrets

import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.secrets.infrastructure.SecretsKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AesGcmSecretCipherTest {
    private val key = ByteArray(32) { it.toByte() }
    private val cipher = AesGcmSecretCipher(key)

    @Test
    fun sealedValueOpensWithTheSameContext() = runTest {
        val envelope = cipher.seal("hunter2-secret".encodeToByteArray(), "secret:a:b")

        assertTrue(envelope.startsWith("v1:"), "Versioned envelope")
        assertEquals("hunter2-secret", cipher.open(envelope, "secret:a:b")?.decodeToString())
    }

    @Test
    fun twoSealsOfTheSameValueDiffer() = runTest {
        val value = "same-value".encodeToByteArray()

        assertNotEquals(cipher.seal(value, "c"), cipher.seal(value, "c"), "A fresh nonce each time")
    }

    @Test
    fun anotherContextOrKeyDoesNotOpen() = runTest {
        val envelope = cipher.seal("value-123".encodeToByteArray(), "secret:org-a:1")

        assertNull(cipher.open(envelope, "secret:org-b:1"), "Bound to its row")
        assertNull(AesGcmSecretCipher(ByteArray(32)).open(envelope, "secret:org-a:1"), "Bound to the key")
        assertNull(cipher.open(envelope, " "), "A blank context never opens")
    }

    @Test
    fun malformedEnvelopesDoNotOpen() = runTest {
        assertNull(cipher.open("v2:AAAA", "c"), "Unknown version")
        assertNull(cipher.open("v1:!!!", "c"), "Not base64")
        assertNull(cipher.open("v1:AAAA", "c"), "Shorter than a nonce and a tag")
        val envelope = cipher.seal("tamper-me".encodeToByteArray(), "c")
        val tampered = envelope.dropLast(2) + if (envelope.endsWith("AA")) "BA" else "AA"
        assertNull(cipher.open(tampered, "c"), "A modified ciphertext fails its tag")
    }

    @Test
    fun aTeamCodeEnvelopeOpensWithItsKeyAndContext() = runTest {
        // Written by AES-GCM with the layout of TeamCode's CredentialCipher (nonce 07×12, key 00..1f).
        val teamCode = AesGcmSecretCipher(SecretsKey.decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "test"))
        val envelope = "v1:BwcHBwcHBwcHBwcHaALWAxtqtpml8nfl3yKc-tM56Sz7kuJrm8_oBVemPenmkjyKoqes7F6K"

        assertContentEquals(
            "ghp_teamcode_shared_secret".encodeToByteArray(),
            teamCode.open(envelope, "variant:team-1:variant-1"),
        )
    }

    @Test
    fun sealingWithoutContextAndWrongKeySizesAreRefused() = runTest {
        assertFailsWith<IllegalArgumentException> { cipher.seal(byteArrayOf(1), "") }
        assertFailsWith<IllegalArgumentException> { AesGcmSecretCipher(ByteArray(16)) }
    }
}
