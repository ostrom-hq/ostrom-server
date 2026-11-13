// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.domain.CloudSessionsSwitch
import run.ostrom.server.integrations.github.domain.GitHubAppCredentials
import run.ostrom.server.integrations.github.domain.GitHubAppSource
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

class GitHubIntegrationFeaturesTest {
    private class FixedApps(private val app: GitHubAppCredentials) : GitHubAppSource {
        override suspend fun current(): GitHubAppCredentials = app

        override fun forget() = Unit
    }

    private fun koin(app: GitHubAppCredentials, cloud: Boolean = true) = koinApplication {
        modules(
            module {
                single<GitHubAppSource> { FixedApps(app) }
                single<CloudSessionsSwitch> { CloudSessionsSwitch { cloud } }
            },
        )
    }.koin

    @Test
    fun gitHubIsListedOnceTheInstanceHasAnApp() = runTest {
        val features = GitHubIntegrationModule.features(koin(GitHubAppCredentials(appId = "42", slug = "ostrom")))
        assertEquals(setOf("github.v1"), features)
    }

    @Test
    fun linksAreListedOnceTheAppsOAuthClientIsKnown() = runTest {
        val features = GitHubIntegrationModule.features(koin(linkingApp()))
        assertEquals(setOf("github.v1", "github.link.v1"), features)
        val noSecret = GitHubAppCredentials(appId = "42", clientId = "Iv1.test")
        assertEquals(setOf("github.v1"), GitHubIntegrationModule.features(koin(noSecret)), "No secret: no link")
        val noCloud = GitHubIntegrationModule.features(koin(linkingApp(), cloud = false))
        assertEquals(setOf("github.v1"), noCloud, "No cloud sessions: nothing to link for")
    }

    @Test
    fun gitHubIsNotListedWithoutAnApp() = runTest {
        assertEquals(emptySet(), GitHubIntegrationModule.features(koin(GitHubAppCredentials())), "No App yet")
    }
}
