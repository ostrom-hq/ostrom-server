// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.presentation

import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.gates.presentation.requireAnyScope
import dev.ostrom.server.integrations.github.domain.GitHubRepo
import dev.ostrom.server.integrations.github.domain.GitHubTeam
import dev.ostrom.server.integrations.github.domain.NamePage
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requireOrgPrincipal
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.auth.requireScope
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.resources.Resource
import io.ktor.server.application.ApplicationCall
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/repos`: the repositories of the caller's organization (from the credential), contract `listRepos`.
 *
 * @property after The `next` of the previous page.
 * @property limit The page size, 1 to 100 (100 by default).
 */
@Serializable
@Resource("/api/v1/repos")
class ReposPath(val after: String? = null, val limit: Int? = null)

/**
 * `GET /api/v1/orgs/{org}/teams`: the teams of an organization the caller belongs to (contract `listTeams`, served
 * under `/orgs/{org}`, decision 1 of the wave).
 *
 * @property org The organization's id.
 * @property after The `next` of the previous page.
 * @property limit The page size, 1 to 100 (100 by default).
 */
@Serializable
@Resource("/api/v1/orgs/{org}/teams")
class OrgTeamsPath(val org: String, val after: String? = null, val limit: Int? = null)

/**
 * A repository (contract `Repo`).
 *
 * @property name Its name.
 * @property description Its description.
 * @property language Its main language.
 * @property defaultBranch Its default branch.
 * @property archived Whether it is archived.
 * @property pushedAt Its last push, ISO 8601.
 * @property owners The slugs of the GitHub teams granted it.
 */
@Serializable
data class RepoResponse(
    val name: String,
    val description: String? = null,
    val language: String? = null,
    val defaultBranch: String,
    val archived: Boolean,
    val pushedAt: String? = null,
    val owners: List<String>,
)

/**
 * A team (contract `Team`).
 *
 * @property slug Its slug.
 * @property name Its name.
 * @property members The logins of its members.
 * @property repos The names of its repositories.
 */
@Serializable
data class TeamResponse(val slug: String, val name: String, val members: List<String>, val repos: List<String>)

/**
 * A page of repositories.
 *
 * @property items The repositories, by name.
 * @property next The `after` of the next page, absent on the last.
 */
@Serializable
data class ReposResponse(val items: List<RepoResponse>, val next: String? = null)

/**
 * A page of teams.
 *
 * @property items The teams, by slug.
 * @property next The `after` of the next page, absent on the last.
 */
@Serializable
data class TeamsResponse(val items: List<TeamResponse>, val next: String? = null)

/** @return The contract's `Repo`. */
fun GitHubRepo.toRepoResponse(): RepoResponse =
    RepoResponse(name, description, language, defaultBranch, archived, pushedAt?.toString(), owners)

/** @return The contract's `Team`. */
fun GitHubTeam.toTeamResponse(): TeamResponse = TeamResponse(slug, name, members, repos)

/**
 * The directory routes: a member's credential with `orgs:read` (or a browser session). An agent's token is refused
 * (403), an organization the caller does not belong to is a 404, like an unknown one.
 *
 * @param dependencies The use cases.
 */
fun Route.gitHubDirectoryRoutes(dependencies: GitHubDirectoryRoutesDependencies) = with(dependencies) {
    authenticated {
        get<ReposPath> { params ->
            val actor = actorOf(call, setOf(GateScopes.ALL_USER, ORGS_READ))
            val page: NamePage<GitHubRepo> = repos(actor.orgId, params.after, params.limit)
            call.respond(ReposResponse(page.items.map { it.toRepoResponse() }, page.next))
        }
        get<OrgTeamsPath> { params ->
            call.requireScope(ORGS_READ)
            val orgId = orgs.orgId(params.org)
                ?: throw OstromException(ErrorKind.NOT_FOUND, "Organization not found.")
            val member = call.requireOrgPrincipal(members, orgId)
            val page = teams(member.orgId, params.after, params.limit)
            call.respond(TeamsResponse(page.items.map { it.toTeamResponse() }, page.next))
        }
    }
}

private suspend fun GitHubDirectoryRoutesDependencies.actorOf(call: ApplicationCall, accepted: Set<String>): GateActor {
    val principal = call.requirePrincipal()
    principal.requireAnyScope(accepted)
    return actors.resolve(principal)
        ?: throw OstromException(ErrorKind.FORBIDDEN, "This credential belongs to no organization.")
}

private const val ORGS_READ = "orgs:read"
