// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.instance

import dev.ostrom.server.instance.domain.Instance
import dev.ostrom.server.instance.domain.InstanceRepository
import dev.ostrom.server.instance.infrastructure.InstanceRepositoryImpl
import dev.ostrom.server.instance.presentation.InstanceResponse
import dev.ostrom.server.instance.presentation.SigningKeysResponse
import dev.ostrom.server.kernel.database.MigrationRunnerImpl
import dev.ostrom.server.kernel.signing.InstanceSigner
import dev.ostrom.server.testing.serverTest
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
