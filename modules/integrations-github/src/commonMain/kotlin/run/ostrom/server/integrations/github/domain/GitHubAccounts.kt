// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.domain

/**
 * Who a GitHub user is in Ostrom: the person whose account is linked to that GitHub user (signed in with GitHub, or
 * `ostrom login`). A pull request's gate names its author by this login, the one its approvers are named by, so that
 * an author never says yes to their own merge under another name (AUDIT-SECU-GATES-2 G1).
 */
interface GitHubAccounts {
    /**
     * @param githubUserId The GitHub user's id, which never changes (a login can).
     * @return The login of the Ostrom person linked to it, or null when nobody is.
     */
    suspend fun loginOf(githubUserId: Long): String?
}
