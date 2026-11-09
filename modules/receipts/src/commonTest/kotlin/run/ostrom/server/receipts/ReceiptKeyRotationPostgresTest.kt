// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.receipts

import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.instance.domain.InstanceRepository
import run.ostrom.server.instance.domain.RegisterCurrentSigningKeyUseCase
import run.ostrom.server.instance.domain.SigningKey
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.EnvironmentConfigSource
import run.ostrom.server.kernel.config.LayeredConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.signing.Ed25519InstanceSigner
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A new receipt key is handed over by the key it replaces (app#33, S2-18): `GET /receipts/keys` serves it with
 * `rotation`, the previous key's id and its Ed25519 signature (base64) over `ostrom-receipt-key-rotation/v1\n` and the
 * hex of the new key's 32 raw bytes. A phone that pinned the previous key then trusts the new one.
 */
class ReceiptKeyRotationPostgresTest {
    // The previous key, written before the server starts: RFC 8032's first test key, in PKCS#8 PEM.
    private val previousFile = Path(SystemTemporaryDirectory, "ostrom-previous-key-${Uuid.random().toHexString()}.pem")

    private val overrides = module {
        single<ConfigSource> {
            LayeredConfigSource(
                listOf(
                    MapConfigSource(mapOf("OSTROM_PREVIOUS_SIGNING_KEY_FILE" to previousFile.toString())),
                    EnvironmentConfigSource(),
                ),
            )
        }
    }

    private suspend fun TestServer.keys(): List<JsonObject> =
        (client.get("/api/v1/receipts/keys").body<JsonObject>().getValue("keys") as JsonArray).map { it.jsonObject }

    @Test
    fun theNewKeyIsServedWithItsRotationSignedByThePreviousOne() {
        SystemFileSystem.sink(previousFile).buffered().use { it.writeString(PREVIOUS_PEM) }
        try {
            rotated()
        } finally {
            SystemFileSystem.delete(previousFile)
        }
    }

    private fun rotated() = serverTest(listOf(InstanceModule, ReceiptsModule), overrides) { server ->
        val previous = Ed25519InstanceSigner.loadOrCreate(previousFile.toString())
        // The server started on its new key while the previous one is unknown to it: nothing to hand over.
        assertTrue(server.keys().none { "rotation" in it })
        // The previous key signed this instance's receipts before: the next start hands the new key over.
        val since = Instant.parse("2026-01-01T00:00:00Z")
        server.koin.get<InstanceRepository>()
            .registerKey(SigningKey(previous.keyId, previous.algorithm, previous.publicKeyPem, since))
        server.koin.get<RegisterCurrentSigningKeyUseCase>()()

        val keys = server.keys()
        assertEquals(listOf(previous.keyId), keys.take(1).map { it.getValue("kid").jsonPrimitive.content })
        assertNull(keys.first()["rotation"], "The previous key was handed over by nobody")
        val next = keys.last()
        val rotation = next.getValue("rotation").jsonObject
        assertEquals(previous.keyId, rotation.getValue("kid").jsonPrimitive.content)
        val signature = Base64.decode(rotation.getValue("signature").jsonPrimitive.content)
        val pem = next.getValue("publicKeyPem").jsonPrimitive.content.lines()
        val raw = Base64.decode(pem.filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString(""))
            .takeLast(RAW_KEY_BYTES)
        val message = "ostrom-receipt-key-rotation/v1\n${raw.toByteArray().toHexString()}".encodeToByteArray()
        assertTrue(previous.verify(message, signature), "The previous key signed the new key's raw bytes")
    }

    private companion object {
        const val RAW_KEY_BYTES = 32
        const val PREVIOUS_PEM = "-----BEGIN PRIVATE KEY-----\n" +
            "MC4CAQAwBQYDK2VwBCIEIJ1hsZ3v/VpguoRK9JLsLMREScVpezJpGXA7rAMcrn9g\n-----END PRIVATE KEY-----\n"
    }
}
