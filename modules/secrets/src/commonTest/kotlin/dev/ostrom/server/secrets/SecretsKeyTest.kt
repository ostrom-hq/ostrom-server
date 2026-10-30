package dev.ostrom.server.secrets

import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.secrets.infrastructure.SecretsKey
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SecretsKeyTest {
    @Test
    fun theVariableWinsOverTheFile() {
        val key = SecretsKey.load(
            MapConfigSource(
                mapOf(
                    "OSTROM_SECRETS_KEY" to "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                    "OSTROM_SECRETS_KEY_FILE" to "/nonexistent/never-read",
                ),
            ),
        )

        assertContentEquals(ByteArray(32) { it.toByte() }, key)
    }

    @Test
    fun theFileIsCreatedOnceThenReadBack() {
        val path = Path(SystemTemporaryDirectory, "ostrom-secrets-${Uuid.random()}", "secrets.key")
        val config = MapConfigSource(mapOf("OSTROM_SECRETS_KEY_FILE" to path.toString()))
        try {
            val created = SecretsKey.load(config)

            assertEquals(32, created.size, "A 256-bit key")
            assertTrue(SystemFileSystem.exists(path), "Written for the next start")
            assertContentEquals(created, SecretsKey.load(config), "The same key at the next start")
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
            path.parent?.let { SystemFileSystem.delete(it, mustExist = false) }
        }
    }

    @Test
    fun malformedKeysStopTheStart() {
        assertFailsWith<InvalidConfigException> { SecretsKey.decode("not base64 !", "x") }
        assertFailsWith<InvalidConfigException> { SecretsKey.decode("AAAA", "x") }
    }
}
