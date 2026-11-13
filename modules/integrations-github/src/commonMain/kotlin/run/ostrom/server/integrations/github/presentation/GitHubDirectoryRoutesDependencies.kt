// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.presentation

import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.integrations.github.domain.ListGitHubReposUseCase
import run.ostrom.server.integrations.github.domain.ListGitHubTeamsUseCase
import run.ostrom.server.integrations.github.domain.OrgReferences
import run.ostrom.server.kernel.auth.PrincipalOrgResolver

/**
 * What the directory routes need.
 *
 * @property actors Resolves the caller's organization from the credential (`GET /repos`).
 * @property members Resolves the caller as a member of the organization of the path (`GET /orgs/{org}/teams`).
 * @property orgs Reads the `{org}` of the path.
 * @property repos Lists the repositories.
 * @property teams Lists the teams.
 */
data class GitHubDirectoryRoutesDependencies(
    val actors: GateActorResolver,
    val members: PrincipalOrgResolver,
    val orgs: OrgReferences,
    val repos: ListGitHubReposUseCase,
    val teams: ListGitHubTeamsUseCase,
)
