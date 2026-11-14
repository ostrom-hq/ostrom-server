// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.domain.GitHubAppCredentials
import run.ostrom.server.integrations.github.infrastructure.AppGitHubSignIn
import run.ostrom.server.integrations.github.infrastructure.GitHubHost
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsValues
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Sign-in with GitHub through the instance's App, as the identity module reads it (its GitHubAppSignIn port). */
class AppGitHubSignInTest {
    private val apps = SettableApps()
    private val host = GitHubHost(
        InstanceSettings {
            if (it == "github") {
                SettingsValues(mapOf("webUrl" to "https://ghe.example", "apiUrl" to "https://ghe.example/api/v3"))
            } else {
                SettingsValues.EMPTY
            }
        },
    )

    @Test
    fun theStoredAppsOAuthClientOnTheIntegrationsGitHubSignsPeopleIn() = runTest {
        host.refresh()
        val client = AppGitHubSignIn(apps, host).client()
        assertEquals(CLIENT_ID to "client-secret", client?.clientId to client?.clientSecret)
        assertEquals("https://ghe.example" to "https://ghe.example/api/v3", client?.webUrl to client?.apiUrl)
        assertFalse("client-secret" in client.toString(), "The secret never appears in a log")
    }

    @Test
    fun anAppWithoutAUsableClientSignsNobodyIn() = runTest {
        // A secret that no longer opens (the secrets key changed) reads as absent, and so does no App at all.
        apps.app = GitHubAppCredentials(appId = "42", slug = "ostrom", clientId = CLIENT_ID)
        assertNull(AppGitHubSignIn(apps, host).client())
        apps.app = GitHubAppCredentials()
        assertNull(AppGitHubSignIn(apps, host).client())
    }
}
