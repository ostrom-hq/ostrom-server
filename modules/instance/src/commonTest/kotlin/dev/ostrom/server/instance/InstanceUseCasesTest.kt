// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.instance

import dev.ostrom.server.instance.domain.GetCurrentSigningKeyUseCaseImpl
import dev.ostrom.server.instance.domain.GetOrCreateInstanceUseCaseImpl
import dev.ostrom.server.instance.domain.Instance
import dev.ostrom.server.instance.domain.InstanceKeyringImpl
import dev.ostrom.server.instance.domain.InstanceRepository
import dev.ostrom.server.instance.domain.ListSigningKeysUseCaseImpl
import dev.ostrom.server.instance.domain.RegisterCurrentSigningKeyUseCaseImpl
import dev.ostrom.server.instance.domain.SigningKey
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            if (keys.none { it.keyId == key.keyId }) keys += key
        }
        override suspend fun listKeys(): List<SigningKey> = keys.toList()
    }

    private class SettableClock(var now: Instant) : Clock {
        override fun now(): Instant = now
    }

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
        val useCase = RegisterCurrentSigningKeyUseCaseImpl(repository, Ed25519InstanceSigner.generate(), clock)

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

        val current = RegisterCurrentSigningKeyUseCaseImpl(repository, signer, clock)()

        assertEquals(signer.keyId, current.keyId)
        assertEquals(listOf(old, current), ListSigningKeysUseCaseImpl(repository)())
        assertEquals(current, GetCurrentSigningKeyUseCaseImpl(repository, signer)())
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
        RegisterCurrentSigningKeyUseCaseImpl(repository, past, clock)()
        val ring = InstanceKeyringImpl(ListSigningKeysUseCaseImpl(repository), current)
        val data = "line".encodeToByteArray()

        assertEquals(true, ring.verify(current.keyId, data, current.sign(data)))
        assertEquals(true, ring.verify(past.keyId, data, past.sign(data)), "A key is never forgotten")
        assertEquals(false, ring.verify(past.keyId, data, current.sign(data)))
        assertEquals(null, ring.verify("0000000000000000", data, past.sign(data)), "Never a key of the instance")

        // A key another replica started with after this one read the list: read again, then known.
        val newer = Ed25519InstanceSigner.generate()
        RegisterCurrentSigningKeyUseCaseImpl(repository, newer, clock)()
        assertEquals(true, ring.verify(newer.keyId, data, newer.sign(data)))
        assertEquals(true, ring.verify(past.keyId, data, past.sign(data)))
    }
}
