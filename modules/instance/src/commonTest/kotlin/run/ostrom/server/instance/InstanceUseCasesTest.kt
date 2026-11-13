// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.instance

import run.ostrom.server.instance.domain.GetCurrentSigningKeyUseCaseImpl
import run.ostrom.server.instance.domain.GetOrCreateInstanceUseCaseImpl
import run.ostrom.server.instance.domain.Instance
import run.ostrom.server.instance.domain.InstanceKeyringImpl
import run.ostrom.server.instance.domain.InstanceRepository
import run.ostrom.server.instance.domain.KeyRotation
import run.ostrom.server.instance.domain.ListSigningKeysUseCaseImpl
import run.ostrom.server.instance.domain.PreviousSigningKey
import run.ostrom.server.instance.domain.RegisterCurrentSigningKeyUseCaseImpl
import run.ostrom.server.instance.domain.SigningKey
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.signing.Ed25519InstanceSigner
import run.ostrom.server.kernel.signing.InstanceSigner
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class InstanceUseCasesTest {
    // Kotlin/Native has no MockK: fakes are written by hand (docs/MODULES.md).
    private class InMemoryInstanceRepository : InstanceRepository {
        var instance: Instance? = null
        val keys = mutableListOf<SigningKey>()

        override suspend fun get(): Instance? = instance
        override suspend fun createIfAbsent(instance: Instance): Instance =
            this.instance ?: instance.also { this.instance = it }
        override suspend fun registerKey(key: SigningKey) {
            val known = keys.indexOfFirst { it.keyId == key.keyId }
            when {
                known < 0 -> keys += key
                keys[known].rotation == null -> keys[known] = keys[known].copy(rotation = key.rotation)
            }
        }
        override suspend fun listKeys(): List<SigningKey> = keys.toList()
    }

    private class SettableClock(var now: Instant) : Clock {
        override fun now(): Instant = now
    }

    private class RecordingLogger(val lines: MutableList<String> = mutableListOf()) : Logger {
        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            lines += "$level $message"
        }

        override fun with(fields: Map<String, Any?>): Logger = this
    }

    private val noPrevious = PreviousSigningKey { null }
    private val logger = RecordingLogger()

    private fun register(repository: InstanceRepository, signer: InstanceSigner, clock: Clock, by: InstanceSigner?) =
        RegisterCurrentSigningKeyUseCaseImpl(repository, signer, clock, { by }, logger)

    @Test
    fun createsTheInstanceOnceThenReturnsTheSameOne() = runTest {
        val repository = InMemoryInstanceRepository()
        val clock = SettableClock(Instant.parse("2026-10-30T01:00:00Z"))
        val useCase = GetOrCreateInstanceUseCaseImpl(repository, clock)

        val first = useCase()
        clock.now = Instant.parse("2026-11-01T00:00:00Z")
        val second = useCase()

        assertEquals(first, second)
        assertEquals(Instant.parse("2026-10-30T01:00:00Z"), second.createdAt)
    }

    @Test
    fun registeringTheSameKeyTwiceKeepsItsFirstDate() = runTest {
        val repository = InMemoryInstanceRepository()
        val clock = SettableClock(Instant.parse("2026-10-30T01:00:00Z"))
        val useCase = RegisterCurrentSigningKeyUseCaseImpl(
            repository, Ed25519InstanceSigner.generate(), clock, noPrevious, logger,
        )

        val first = useCase()
        clock.now = Instant.parse("2026-11-01T00:00:00Z")
        val second = useCase()

        assertEquals(first, second)
        assertEquals(1, repository.keys.size)
    }

    @Test
    fun registeringANewKeyKeepsThePreviousOnes() = runTest {
        val repository = InMemoryInstanceRepository()
        val old = SigningKey("0123456789abcdef", "ed25519", "old-pem", Instant.parse("2026-01-01T00:00:00Z"))
        repository.keys += old
        val signer = Ed25519InstanceSigner.generate()
        val clock = SettableClock(Instant.parse("2026-10-30T01:00:00Z"))

        val current = RegisterCurrentSigningKeyUseCaseImpl(repository, signer, clock, noPrevious, logger)()

        assertEquals(signer.keyId, current.keyId)
        assertEquals(listOf(old, current), ListSigningKeysUseCaseImpl(repository)())
        assertEquals(current, GetCurrentSigningKeyUseCaseImpl(repository, signer)())
    }

    // A new key is handed over by the key it replaces, if this instance signed with it (docs/receipt-key-rotation.md):
    // the app checks the signature over the context line and the hex of the new key's raw bytes (app#33).
    @Test
    fun theKeyThisInstanceSignedWithBeforeHandsTheNewOneOver() = runTest {
        val repository = InMemoryInstanceRepository()
        val clock = SettableClock(Instant.parse("2026-10-30T01:00:00Z"))
        val previous = Ed25519InstanceSigner.generate()
        val current = Ed25519InstanceSigner.generate()
        register(repository, previous, clock, null)()
        clock.now = Instant.parse("2026-11-03T12:00:00Z")

        val rotated = register(repository, current, clock, previous)()

        val rotation = assertNotNull(rotated.rotation)
        assertEquals(previous.keyId, rotation.keyId)
        val raw = Base64.decode(current.publicKeyPem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
            .takeLast(32).toByteArray()
        val message = "ostrom-receipt-key-rotation/v1\n${raw.toHexString()}".encodeToByteArray()
        assertContentEquals(message, KeyRotation.message(current.publicKeyPem))
        assertTrue(previous.verify(message, Base64.decode(rotation.signature)))
        // A later start changes nothing, and the previous key itself was handed over by nobody.
        assertEquals(rotated, register(repository, current, clock, previous)())
        assertNull(repository.keys.first().rotation)
        assertEquals(emptyList(), logger.lines)
    }

    // A key this instance never signed with, or the current key itself, never vouches for the current key.
    @Test
    fun aForeignOrTheSameKeyHandsNothingOver() = runTest {
        val repository = InMemoryInstanceRepository()
        val clock = SettableClock(Instant.parse("2026-10-30T01:00:00Z"))
        val current = Ed25519InstanceSigner.generate()

        assertNull(register(repository, current, clock, Ed25519InstanceSigner.generate())().rotation)
        assertNull(register(repository, current, clock, current)().rotation)
        assertEquals(
            listOf(
                "WARN The previous signing key hands nothing over: this instance never signed with it.",
                "WARN The previous signing key hands nothing over: the previous key is the current one.",
            ),
            logger.lines,
        )
    }

    @Test
    fun theCurrentKeyIsUnavailableUntilTheStartRecordsIt() = runTest {
        val error = assertFailsWith<OstromException> {
            GetCurrentSigningKeyUseCaseImpl(InMemoryInstanceRepository(), Ed25519InstanceSigner.generate())()
        }
        assertEquals(ErrorKind.UNAVAILABLE, error.kind)
    }

    @Test
    fun theKeyringVerifiesWithTheCurrentKeyAndEveryPastOneButNoOther() = runTest {
        val repository = InMemoryInstanceRepository()
        val past = Ed25519InstanceSigner.generate()
        val current = Ed25519InstanceSigner.generate()
        val clock = SettableClock(Instant.parse("2026-10-30T01:00:00Z"))
        RegisterCurrentSigningKeyUseCaseImpl(repository, past, clock, noPrevious, logger)()
        val ring = InstanceKeyringImpl(ListSigningKeysUseCaseImpl(repository), current)
        val data = "line".encodeToByteArray()

        assertEquals(true, ring.verify(current.keyId, data, current.sign(data)))
        assertEquals(true, ring.verify(past.keyId, data, past.sign(data)), "A key is never forgotten")
        assertEquals(false, ring.verify(past.keyId, data, current.sign(data)))
        assertEquals(null, ring.verify("0000000000000000", data, past.sign(data)), "Never a key of the instance")

        // A key another replica started with after this one read the list: read again, then known.
        val newer = Ed25519InstanceSigner.generate()
        RegisterCurrentSigningKeyUseCaseImpl(repository, newer, clock, noPrevious, logger)()
        assertEquals(true, ring.verify(newer.keyId, data, newer.sign(data)))
        assertEquals(true, ring.verify(past.keyId, data, past.sign(data)))
    }
}
