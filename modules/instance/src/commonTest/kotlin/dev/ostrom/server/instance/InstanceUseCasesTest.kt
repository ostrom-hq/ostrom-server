package dev.ostrom.server.instance

import dev.ostrom.server.instance.domain.GetCurrentSigningKeyUseCaseImpl
import dev.ostrom.server.instance.domain.GetOrCreateInstanceUseCaseImpl
import dev.ostrom.server.instance.domain.Instance
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
}
