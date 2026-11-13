// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.presentation

import run.ostrom.server.integrations.github.domain.GitHubLinks
import run.ostrom.server.kernel.auth.PrincipalOrgResolver

/**
 * What the GitHub link routes need.
 *
 * @property links The links.
 * @property members The member a request acts for, and their role.
 * @property webOrigin The console, where the web flow sends the browser back.
 */
data class GitHubLinkRoutesDependencies(
    val links: GitHubLinks,
    val members: PrincipalOrgResolver,
    val webOrigin: String,
)
