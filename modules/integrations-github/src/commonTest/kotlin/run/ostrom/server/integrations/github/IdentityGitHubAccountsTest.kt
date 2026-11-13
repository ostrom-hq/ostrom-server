// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.infrastructure.IdentityGitHubAccounts
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IdentityGitHubAccountsTest {
    // An approver's GitHub accounts, for a pull request whose author is linked to nobody (Q-GATES-G1).
    @Test
    fun aPersonsGitHubAccountsAreTheOnesTheyLinked() = runTest {
        val users = LinkedGitHubUsers(mapOf(7L to "dev", 9L to "dev", 8L to "other"))
        val accounts = IdentityGitHubAccounts(users, users)

        assertEquals(listOf(7L, 9L), accounts.gitHubIdsOf("Dev").sorted())
        assertEquals(emptyList(), accounts.gitHubIdsOf("nobody"))
    }
}
