// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.instance

import run.ostrom.server.instance.domain.Instance
import run.ostrom.server.instance.domain.InstanceRepository
import run.ostrom.server.instance.domain.KeyRotation
import run.ostrom.server.instance.domain.SigningKey
import run.ostrom.server.instance.infrastructure.InstanceRepositoryImpl
import run.ostrom.server.instance.presentation.InstanceResponse
import run.ostrom.server.instance.presentation.SigningKeysResponse
import run.ostrom.server.kernel.database.MigrationRunnerImpl
import run.ostrom.server.kernel.signing.InstanceSigner
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Against a real PostgreSQL (CI service): the driver, the migrations and the repositories, on the JVM and natively. */
class InstancePostgresTest {
    @Test
    fun repositoryWritesThenReadsTheInstance() = serverTest(listOf(InstanceModule)) { server ->
        val repository = server.koin.get<InstanceRepository>()
        // initialize() already created the instance at start; a second creation keeps the first one.
        val existing = assertNotNull(repository.get())
        val other = Instance(Uuid.random(), Instant.parse("2030-01-01T00:00:00Z"))

        assertEquals(existing, repository.createIfAbsent(other))
        assertEquals(existing, repository.get())
    }

    @Test
    fun instanceRouteReturnsAStableIdAndTheCurrentKey() = serverTest(listOf(InstanceModule)) { server ->
        val signer = server.koin.get<InstanceSigner>()

        val first = server.client.get("/api/v1/instance")
        assertEquals(HttpStatusCode.OK, first.status)
        val instance = first.body<InstanceResponse>()
        assertEquals(instance.id, server.client.get("/api/v1/instance").body<InstanceResponse>().id)
        assertEquals(signer.keyId, instance.signingKey.keyId)
        assertEquals(signer.publicKeyPem, instance.signingKey.publicKeyPem)

        val keys = server.client.get("/api/v1/instance/keys").body<SigningKeysResponse>()
        assertEquals(listOf(signer.keyId), keys.keys.map { it.keyId })
    }

    // A rotation is recorded on a key that has none, then never changes (docs/receipt-key-rotation.md).
    @Test
    fun aKeyTakesItsFirstRotationOnly() = serverTest(listOf(InstanceModule)) { server ->
        val repository = server.koin.get<InstanceRepository>()
        val at = Instant.parse("2026-11-03T12:00:00Z")
        val key = SigningKey("00000000000000aa", "ed25519", "pem", at)
        repository.registerKey(key)
        repository.registerKey(key.copy(rotation = KeyRotation("00000000000000bb", "c2ln")))
        repository.registerKey(key.copy(rotation = KeyRotation("00000000000000cc", "b3RoZXI=")))

        val stored = repository.listKeys().single { it.keyId == key.keyId }
        assertEquals(KeyRotation("00000000000000bb", "c2ln"), stored.rotation)
        assertEquals(at, stored.firstSeenAt)
    }

    @Test
    fun repositoryReadsNoInstanceBeforeItsCreation() = serverTest(emptyList()) { server ->
        // Migrations are the owner's, like `ostrom-server migrate`; the serving role cannot create a table.
        MigrationRunnerImpl(server.database.owner, server.koin.get())
            .migrate(InstanceModule.name, InstanceModule.migrations)
        val repository = InstanceRepositoryImpl(server.driver)
        assertNull(repository.get())
        assertEquals(emptyList(), repository.listKeys())
    }
}
