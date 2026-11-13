// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets

import run.ostrom.server.kernel.config.InvalidConfigException
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.secrets.infrastructure.SecretsKey
import run.ostrom.server.secrets.infrastructure.createOwnerOnlyFile
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    @Test
    fun anExistingKeyFileIsNeverOverwrittenAndNoTemporaryFileStays() {
        val directory = Path(SystemTemporaryDirectory, "ostrom-secrets-${Uuid.random()}")
        SystemFileSystem.createDirectories(directory)
        val path = Path(directory, "secrets.key").toString()
        try {
            assertTrue(createOwnerOnlyFile(path, "first"))
            assertFalse(createOwnerOnlyFile(path, "second"), "A second writer loses")
            assertEquals("first", SystemFileSystem.source(Path(path)).buffered().use { it.readString() })
            assertEquals(
                listOf("secrets.key"),
                SystemFileSystem.list(directory).map {
                    it.name
                },
                "Written aside, then linked",
            )
        } finally {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun blankVariablesFallBackToTheDefaultFile() =
        assertTheDefaultFileIsUsed(mapOf(SecretsKey.KEY_VARIABLE to " ", SecretsKey.FILE_VARIABLE to " "))

    @Test
    fun noVariablesFallBackToTheDefaultFile() = assertTheDefaultFileIsUsed(emptyMap())

    private fun assertTheDefaultFileIsUsed(variables: Map<String, String>) {
        // A bare file name: the key file has no parent directory to create.
        val path = "ostrom-secrets-${Uuid.random()}.key"
        try {
            val created = SecretsKey.load(MapConfigSource(variables), defaultFile = path)
            assertEquals(32, created.size)
            assertTrue(SystemFileSystem.exists(Path(path)))
        } finally {
            SystemFileSystem.delete(Path(path), mustExist = false)
        }
    }
}
