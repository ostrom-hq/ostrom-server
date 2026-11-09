// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github

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

    private fun koin(app: GitHubAppCredentials) =
        koinApplication { modules(module { single<GitHubAppSource> { FixedApps(app) } }) }.koin

    @Test
    fun gitHubIsListedOnceTheInstanceHasAnApp() = runTest {
        val features = GitHubIntegrationModule.features(koin(GitHubAppCredentials(appId = "42", slug = "ostrom")))
        assertEquals(setOf("github.v1"), features)
    }

    @Test
    fun gitHubIsNotListedWithoutAnApp() = runTest {
        assertEquals(emptySet(), GitHubIntegrationModule.features(koin(GitHubAppCredentials())), "No App yet")
    }
}
