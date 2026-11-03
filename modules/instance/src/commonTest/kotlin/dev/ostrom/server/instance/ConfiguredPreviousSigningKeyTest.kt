// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.instance

import dev.ostrom.server.instance.infrastructure.ConfiguredPreviousSigningKey
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.kernel.signing.Ed25519InstanceSigner
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class ConfiguredPreviousSigningKeyTest {
    @Test
    fun noRotationIsConfiguredUnlessThePreviousKeyFileIsNamed() = runTest {
        assertNull(ConfiguredPreviousSigningKey(MapConfigSource(emptyMap())).signer())
    }

    @Test
    fun thePreviousKeyIsReadFromItsFile() = runTest {
        val path = Path(SystemTemporaryDirectory, "ostrom-previous-${Uuid.random().toHexString()}.pem").toString()
        try {
            val previous = Ed25519InstanceSigner.loadOrCreate(path)
            val config = MapConfigSource(mapOf("OSTROM_PREVIOUS_SIGNING_KEY_FILE" to path))
            assertEquals(previous.keyId, ConfiguredPreviousSigningKey(config).signer()?.keyId)
        } finally {
            SystemFileSystem.delete(Path(path))
        }
    }
}
