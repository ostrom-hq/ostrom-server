// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.signing

import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.kernel.platform.createPrivateFile
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class Ed25519InstanceSignerTest {
    private fun temporaryDirectory(): Path =
        Path(SystemTemporaryDirectory, "ostrom-test-${Uuid.random().toHexString()}")

    private fun deleteTree(directory: Path) {
        SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(directory)
    }

    private fun read(path: String): String = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

    @Test
    fun signsAndVerifies() = runTest {
        val signer = Ed25519InstanceSigner.generate()
        val data = "receipt".encodeToByteArray()
        val signature = signer.sign(data)

        assertEquals(64, signature.size)
        assertTrue(signer.verify(data, signature))
        assertFalse(signer.verify("tampered".encodeToByteArray(), signature))
        assertFalse(signer.verify(data, signature.copyOf(SIGNATURE_SIZE - 1)), "A short signature is a no, not a throw")
        assertEquals("ed25519", signer.algorithm)
        assertTrue(Regex("[0-9a-f]{16}").matches(signer.keyId))
        assertTrue(signer.publicKeyPem.startsWith("-----BEGIN PUBLIC KEY-----"))
    }

    @Test
    fun createsTheKeyFileOnceThenReloadsTheSameKey() = runTest {
        val directory = temporaryDirectory()
        val path = Path(directory, "nested", "instance-key.pem").toString()
        try {
            val created = Ed25519InstanceSigner.loadOrCreate(path)
            assertTrue(read(path).startsWith("-----BEGIN PRIVATE KEY-----"))
            val reloaded = Ed25519InstanceSigner.loadOrCreate(path)

            assertEquals(created.keyId, reloaded.keyId)
            val data = "package".encodeToByteArray()
            assertTrue(created.verify(data, reloaded.sign(data)))
        } finally {
            deleteTree(Path(directory, "nested"))
            SystemFileSystem.delete(directory)
        }
    }

    // The previous key of a rotation is only read: a missing file is a configuration error, never a new key.
    @Test
    fun loadReadsAnExistingKeyAndNeverCreatesOne() = runTest {
        val directory = temporaryDirectory()
        val path = Path(directory, "previous-key.pem").toString()
        try {
            val missing = assertFailsWith<InvalidConfigException> { Ed25519InstanceSigner.load(path) }
            assertEquals("Signing key file $path does not exist.", missing.message)
            assertFalse(SystemFileSystem.exists(Path(path)))
            val created = Ed25519InstanceSigner.loadOrCreate(path)
            assertEquals(created.keyId, Ed25519InstanceSigner.load(path).keyId)
        } finally {
            deleteTree(directory)
        }
    }

    // A key file that is not an Ed25519 private key stops the start with its path, current key or previous one.
    @Test
    fun aCorruptedKeyFileIsAConfigurationErrorNamingIt() = runTest {
        val directory = temporaryDirectory()
        val path = Path(directory, "instance-key.pem").toString()
        try {
            SystemFileSystem.createDirectories(directory)
            val corrupted = "-----BEGIN PRIVATE KEY-----\nbm9wZQ==\n"
            SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(corrupted) }
            val message = "Signing key file $path is not an Ed25519 private key in PKCS#8 PEM."
            val current = assertFailsWith<InvalidConfigException> { Ed25519InstanceSigner.loadOrCreate(path) }
            assertEquals(message, current.message)
            assertEquals(message, assertFailsWith<InvalidConfigException> { Ed25519InstanceSigner.load(path) }.message)
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun createsAKeyFileWithoutADirectory() = runTest {
        val path = "ostrom-test-${Uuid.random().toHexString()}.pem"
        try {
            val created = Ed25519InstanceSigner.loadOrCreate(path)
            assertEquals(created.keyId, Ed25519InstanceSigner.loadOrCreate(path).keyId)
        } finally {
            SystemFileSystem.delete(Path(path), mustExist = false)
        }
    }

    @Test
    fun keepsTheKeyOfAProcessThatCreatedItFirst() = runTest {
        val directory = temporaryDirectory()
        SystemFileSystem.createDirectories(directory)
        val path = Path(directory, "instance-key.pem").toString()
        try {
            var winner = ""
            val signer = Ed25519InstanceSigner.loadOrCreate(path) { file, _ ->
                // Another replica writes its own key between our existence check and our creation.
                createPrivateFile(file, OTHER_KEY_PEM)
                winner = file
                false
            }
            assertEquals(path, winner)
            assertEquals(Ed25519InstanceSigner.loadOrCreate(path).keyId, signer.keyId)
            assertEquals(OTHER_KEY_PEM, read(path))
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun aPrivateFileIsNeverOverwritten() {
        val directory = temporaryDirectory()
        SystemFileSystem.createDirectories(directory)
        val path = Path(directory, "secret").toString()
        try {
            assertTrue(createPrivateFile(path, "first"))
            assertFalse(createPrivateFile(path, "second"))
            assertEquals("first", read(path))
            assertEquals(listOf("secret"), SystemFileSystem.list(directory).map { it.name }, "No temporary file left")
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun twoGeneratedKeysDiffer() = runTest {
        assertNotEquals(Ed25519InstanceSigner.generate().keyId, Ed25519InstanceSigner.generate().keyId)
    }

    @Test
    fun aPublishedKeyVerifiesWhatItsKeySignedAndNothingElse() = runTest {
        val past = Ed25519InstanceSigner.generate()
        val current = Ed25519InstanceSigner.generate()
        val data = "line".encodeToByteArray()
        val signature = past.sign(data)

        assertTrue(Ed25519InstanceSigner.verifyWith(past.publicKeyPem, data, signature))
        assertFalse(Ed25519InstanceSigner.verifyWith(current.publicKeyPem, data, signature))
        assertFalse(Ed25519InstanceSigner.verifyWith(past.publicKeyPem, "other".encodeToByteArray(), signature))
        assertFalse(Ed25519InstanceSigner.verifyWith(past.publicKeyPem, data, ByteArray(0)))
    }

    @Test
    fun theCurrentKeyringKnowsTheCurrentKeyOnly() = runTest {
        val signer = Ed25519InstanceSigner.generate()
        val ring = CurrentKeyring(signer)
        val data = "line".encodeToByteArray()

        assertEquals(true, ring.verify(signer.keyId, data, signer.sign(data)))
        assertEquals(false, ring.verify(signer.keyId, data, ByteArray(SIGNATURE_SIZE)))
        assertEquals(null, ring.verify("0000000000000000", data, signer.sign(data)))
    }

    private companion object {
        const val SIGNATURE_SIZE = 64

        // A fixed Ed25519 key (PKCS#8 PEM), as another replica would have written it.
        const val OTHER_KEY_PEM = "-----BEGIN PRIVATE KEY-----\n" +
            "MC4CAQAwBQYDK2VwBCIEIAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8g\n" +
            "-----END PRIVATE KEY-----\n"
    }
}
