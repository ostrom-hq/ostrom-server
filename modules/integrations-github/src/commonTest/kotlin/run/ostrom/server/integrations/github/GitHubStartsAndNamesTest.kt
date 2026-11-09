// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.domain.installPage
import run.ostrom.server.integrations.github.domain.isRepositoryName
import run.ostrom.server.integrations.github.presentation.startedElsewhere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AUDIT-SECU-2 B1 (a start a third-party site triggers) and B5 (only a repository's full name in GitHub's path). */
class GitHubStartsAndNamesTest {
    @Test
    fun aStartFromAThirdPartySiteIsRefusedButTheConsolesOwnLinksPass() {
        assertTrue(startedElsewhere("cross-site", null, CONSOLE), "An image or a link on another site")
        assertTrue(startedElsewhere("cross-site", "https://evil.example/page", CONSOLE))
        assertTrue(startedElsewhere("cross-site", "$CONSOLE.evil.example/", CONSOLE), "Not the console's origin")
        assertFalse(startedElsewhere("cross-site", "$CONSOLE/github", CONSOLE), "The console on another site")
        assertFalse(startedElsewhere("cross-site", CONSOLE, CONSOLE), "Its origin only, as a cross-origin referrer")
        for (site in listOf("same-origin", "same-site", "none", null)) {
            assertFalse(startedElsewhere(site, null, CONSOLE), "$site")
        }
    }

    @Test
    fun theInstallPageNamesTheAppOrThereIsNone() {
        assertEquals("$GITHUB/apps/ostrom-dev/installations/new", installPage(GITHUB, "ostrom-dev"))
        assertNull(installPage(GITHUB, null))
    }

    @Test
    fun onlyARepositorysFullNameIsOne() {
        val longest = "o".repeat(39) + "/" + "n".repeat(100)
        for (name in listOf("acme/api", "acme/.github", "a-b/c_d.e-f", "A1/x", longest)) {
            assertTrue(isRepositoryName(name), name)
        }
        val refused = listOf(
            "acme", "acme/", "/api", "acme/..", "acme/.", "x/../../orgs/acme/members", "acme/api?x=1", "acme/api#x",
            "acme/a b", "-acme/api", "o".repeat(40) + "/api", "acme/" + "n".repeat(101), "acme/api/pulls", "",
        )
        for (name in refused) assertFalse(isRepositoryName(name), name)
    }
}

private const val CONSOLE = "https://console.example"
