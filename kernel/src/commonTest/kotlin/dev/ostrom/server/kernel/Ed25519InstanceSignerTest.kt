package dev.ostrom.server.kernel

import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class Ed25519InstanceSignerTest {
    @Test
    fun signsAndVerifies() = runTest {
        val signer = Ed25519InstanceSigner.generate()
        val data = "receipt".encodeToByteArray()
        val signature = signer.sign(data)

        assertEquals(64, signature.size)
        assertTrue(signer.verify(data, signature))
        assertFalse(signer.verify("tampered".encodeToByteArray(), signature))
        assertEquals("ed25519", signer.algorithm)
        assertTrue(Regex("[0-9a-f]{16}").matches(signer.keyId))
        assertTrue(signer.publicKeyPem.startsWith("-----BEGIN PUBLIC KEY-----"))
    }

    @Test
    fun createsTheKeyFileOnceThenReloadsTheSameKey() = runTest {
        val path = "/tmp/ostrom-test-${Uuid.random().toHexString()}/instance-key.pem"
        try {
            val created = Ed25519InstanceSigner.loadOrCreate(path)
            assertTrue(SystemFileSystem.exists(Path(path)))
            val reloaded = Ed25519InstanceSigner.loadOrCreate(path)

            assertEquals(created.keyId, reloaded.keyId)
            val data = "package".encodeToByteArray()
            assertTrue(created.verify(data, reloaded.sign(data)))
        } finally {
            SystemFileSystem.delete(Path(path), mustExist = false)
            Path(path).parent?.let { SystemFileSystem.delete(it, mustExist = false) }
        }
    }
}
