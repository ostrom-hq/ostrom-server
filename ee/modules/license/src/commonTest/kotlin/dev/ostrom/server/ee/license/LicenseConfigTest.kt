// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package dev.ostrom.server.ee.license

import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.kernel.config.MapConfigSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class LicenseConfigTest {
    @Test
    fun withoutAKeyThereIsNoLicense() {
        val config = LicenseConfig.load(MapConfigSource(emptyMap())) { error("no file is read") }
        assertNull(config.licenseKey)
        assertEquals("LicenseConfig(licenseKey=unset)", config.toString())
    }

    @Test
    fun theKeyComesFromTheEnvironmentAndWinsOverTheFile() {
        val config = LicenseConfig.load(
            MapConfigSource(
                mapOf("OSTROM_LICENSE_KEY" to " ostrom-license.v1.a.b \n", "OSTROM_LICENSE_KEY_FILE" to "/run/license"),
            ),
        ) { error("the file is not read when the value is set") }
        assertEquals("ostrom-license.v1.a.b", config.licenseKey)
        assertEquals("LicenseConfig(licenseKey=set)", config.toString(), "the key is never printed")
    }

    @Test
    fun theKeyComesFromAFile() {
        val config = LicenseConfig.load(MapConfigSource(mapOf("OSTROM_LICENSE_KEY_FILE" to "/run/license"))) {
            assertEquals("/run/license", it)
            "ostrom-license.v1.a.b\n"
        }
        assertEquals("ostrom-license.v1.a.b", config.licenseKey)
    }

    @Test
    fun anEmptyKeyFileIsRefused() {
        val error = assertFailsWith<InvalidConfigException> {
            LicenseConfig.load(MapConfigSource(mapOf("OSTROM_LICENSE_KEY_FILE" to "/run/license"))) { " \n" }
        }
        assertEquals("License key file /run/license is empty.", error.message)
    }

    @Test
    fun aMissingKeyFileIsRefused() {
        val error = assertFailsWith<InvalidConfigException> {
            LicenseConfig.load(MapConfigSource(mapOf("OSTROM_LICENSE_KEY_FILE" to "/nonexistent/ostrom-license-test")))
        }
        assertEquals("License key file /nonexistent/ostrom-license-test does not exist.", error.message)
    }

    @Test
    fun theRealFileIsRead() {
        val path = Path(SystemTemporaryDirectory, "ostrom-license-${Uuid.random().toHexString()}.key")
        SystemFileSystem.sink(path).buffered().use { it.writeString("ostrom-license.v1.file.sig\n") }
        try {
            val config = LicenseConfig.load(MapConfigSource(mapOf("OSTROM_LICENSE_KEY_FILE" to path.toString())))
            assertEquals("ostrom-license.v1.file.sig", config.licenseKey)
        } finally {
            SystemFileSystem.delete(path)
        }
    }
}
