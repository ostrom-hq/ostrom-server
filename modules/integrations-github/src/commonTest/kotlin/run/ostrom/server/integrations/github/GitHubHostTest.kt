// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.infrastructure.GITHUB_SETTINGS
import run.ostrom.server.integrations.github.infrastructure.GitHubHost
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsValues
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubHostTest {
    private var values = emptyMap<String, String>()
    private val host = GitHubHost(
        InstanceSettings { if (it == "github") SettingsValues(values) else SettingsValues.EMPTY },
    )

    @Test
    fun gitHubDotComAndOstromUntilTheConsoleSaysOtherwise() = runTest {
        assertEquals(listOf("https://api.github.com", "https://github.com", "Ostrom"), current())
        host.refresh()
        assertEquals(listOf("https://api.github.com", "https://github.com", "Ostrom"), current())

        values = mapOf(
            "apiUrl" to " https://ghe.example/api/v3/ ",
            "webUrl" to "https://ghe.example/",
            "appName" to " Acme ",
        )
        assertEquals("https://api.github.com", host.apiUrl, "Read again only by a refresh")
        host.refresh()
        assertEquals(listOf("https://ghe.example/api/v3", "https://ghe.example", "Acme"), current())

        values = mapOf("apiUrl" to " ", "webUrl" to "", "appName" to " ")
        host.refresh()
        assertEquals(listOf("https://api.github.com", "https://github.com", "Ostrom"), current())
    }

    @Test
    fun theSectionNamesItsFormerVariablesAndChecksItsValues() {
        assertEquals("github", GITHUB_SETTINGS.name)
        val fields = GITHUB_SETTINGS.fields.associateBy { it.name }
        assertEquals(
            mapOf(
                "apiUrl" to "OSTROM_GITHUB_API",
                "webUrl" to "OSTROM_GITHUB_WEB",
                "appName" to "OSTROM_GITHUB_APP_NAME",
            ),
            fields.mapValues { it.value.legacyVariable },
        )
        for (url in listOf("apiUrl", "webUrl")) {
            val check = fields.getValue(url).check
            assertNull(check(" https://ghe.example "))
            assertNull(check("http://localhost:9000"))
            assertEquals("an http:// or https:// URL.", check("ghe.example"))
        }
        val name = fields.getValue("appName").check
        assertNull(name(" Acme Ostrom "))
        assertEquals("1 to 34 characters.", name(" "))
        assertEquals("1 to 34 characters.", name("a".repeat(35)))
    }

    private fun current() = listOf(host.apiUrl, host.webUrl, host.appName)
}
