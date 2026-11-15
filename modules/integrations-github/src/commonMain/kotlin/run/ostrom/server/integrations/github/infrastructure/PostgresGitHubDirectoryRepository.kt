// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.integrations.github.domain.DirectoryState
import run.ostrom.server.integrations.github.domain.GitHubDirectory
import run.ostrom.server.integrations.github.domain.GitHubDirectoryRepository
import run.ostrom.server.integrations.github.domain.GitHubRepo
import run.ostrom.server.integrations.github.domain.GitHubTeam
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.lockUntilCommit
import run.ostrom.server.kernel.database.withOrganization
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [GitHubDirectoryRepository] on PostgreSQL through Exposed. Every call is one transaction of the organization
 * (`ostrom.org_id`), the only rows row level security lets it see or write. A list is read by its primary key
 * `(org_id, name)` or `(org_id, slug)`, keyset from the cursor, bounded by its limit.
 *
 * @param database The serving role's pool.
 */
@Suppress("TooManyFunctions") // One implementation of the module's one port: its reads, writes and cursor checks.
class PostgresGitHubDirectoryRepository(private val database: JdbcDatabase) : GitHubDirectoryRepository {
    override suspend fun state(orgId: Uuid): DirectoryState? = database.withOrganization(orgId) {
        val row = directoryState(orgId).firstOrNull()
        if (row == null) {
            null
        } else {
            DirectoryState(row[GitHubDirectorySyncs.syncedAt], row[GitHubDirectorySyncs.checkedAt])
        }
    }

    override suspend fun claim(orgId: Uuid, now: Instant, staleBefore: Instant): Boolean =
        database.withOrganization(orgId) {
            val s = GitHubDirectorySyncs
            s.update({ (s.orgId eq orgId) and (s.checkedAt less staleBefore) }) { it[checkedAt] = now } == 1
        }

    override suspend fun replace(orgId: Uuid, directory: GitHubDirectory, at: Instant) {
        database.withOrganization(orgId) {
            // One replacement of an organization at a time: the console lists the repositories and the teams at once,
            // and two first reads would otherwise insert the same keys side by side (a unique violation).
            lockUntilCommit("github-directory:$orgId")
            val r = GitHubRepos
            val t = GitHubTeams
            r.deleteWhere { r.orgId eq orgId }
            r.batchInsert(directory.repos, shouldReturnGeneratedValues = false) { repo ->
                this[r.orgId] = orgId
                this[r.name] = repo.name
                this[r.description] = repo.description
                this[r.language] = repo.language
                this[r.defaultBranch] = repo.defaultBranch
                this[r.archived] = repo.archived
                this[r.pushedAt] = repo.pushedAt
                this[r.owners] = NamesColumn.encode(repo.owners)
            }
            t.deleteWhere { t.orgId eq orgId }
            t.batchInsert(directory.teams, shouldReturnGeneratedValues = false) { team ->
                this[t.orgId] = orgId
                this[t.slug] = team.slug
                this[t.name] = team.name
                this[t.members] = NamesColumn.encode(team.members)
                this[t.repos] = NamesColumn.encode(team.repos)
            }
            GitHubDirectorySyncs.upsert(GitHubDirectorySyncs.orgId) {
                it[GitHubDirectorySyncs.orgId] = orgId
                it[syncedAt] = at
                it[checkedAt] = at
            }
        }
    }

    override suspend fun forget(orgId: Uuid) {
        database.withOrganization(orgId) {
            GitHubDirectorySyncs.deleteWhere { GitHubDirectorySyncs.orgId eq orgId }
        }
    }

    override suspend fun repos(orgId: Uuid, after: String?, limit: Int): List<GitHubRepo> =
        database.withOrganization(orgId) { reposPage(orgId, after, limit).map { it.toRepo() } }

    override suspend fun teams(orgId: Uuid, after: String?, limit: Int): List<GitHubTeam> =
        database.withOrganization(orgId) { teamsPage(orgId, after, limit).map { it.toTeam() } }

    override suspend fun hasRepo(orgId: Uuid, name: String): Boolean =
        database.withOrganization(orgId) { repoKey(orgId, name).any() }

    override suspend fun hasTeam(orgId: Uuid, slug: String): Boolean =
        database.withOrganization(orgId) { teamKey(orgId, slug).any() }

    private companion object {
        fun ResultRow.toRepo() = GitHubRepo(
            name = this[GitHubRepos.name],
            description = this[GitHubRepos.description],
            language = this[GitHubRepos.language],
            defaultBranch = this[GitHubRepos.defaultBranch],
            archived = this[GitHubRepos.archived],
            pushedAt = this[GitHubRepos.pushedAt],
            owners = NamesColumn.decode(this[GitHubRepos.owners]),
        )

        fun ResultRow.toTeam() = GitHubTeam(
            slug = this[GitHubTeams.slug],
            name = this[GitHubTeams.name],
            members = NamesColumn.decode(this[GitHubTeams.members]),
            repos = NamesColumn.decode(this[GitHubTeams.repos]),
        )
    }
}

// The request-path reads of the directory, which GitHubDirectoryPlansTest explains: by the primary key, keyset from
// the cursor (`after`), bounded by `limit`.

/** When the organization's directory was read and last checked. */
internal fun directoryState(orgId: Uuid): Query {
    val s = GitHubDirectorySyncs
    return s.select(s.syncedAt, s.checkedAt).where { s.orgId eq orgId }
}

/** One repository by its key, to check a cursor. */
internal fun repoKey(orgId: Uuid, name: String): Query {
    val r = GitHubRepos
    return r.select(r.name).where { (r.orgId eq orgId) and (r.name eq name) }
}

/** One team by its key, to check a cursor. */
internal fun teamKey(orgId: Uuid, slug: String): Query {
    val t = GitHubTeams
    return t.select(t.slug).where { (t.orgId eq orgId) and (t.slug eq slug) }
}

/** A page of repositories by name, after the cursor [after] when there is one. */
internal fun reposPage(orgId: Uuid, after: String?, limit: Int): Query {
    val r = GitHubRepos
    val columns = listOf(r.name, r.description, r.language, r.defaultBranch, r.archived, r.pushedAt, r.owners)
    val inOrg = r.orgId eq orgId
    return r.select(columns).where { if (after == null) inOrg else inOrg and (r.name greater after) }
        .orderBy(r.name).limit(limit)
}

/** A page of teams by slug, after the cursor [after] when there is one. */
internal fun teamsPage(orgId: Uuid, after: String?, limit: Int): Query {
    val t = GitHubTeams
    val inOrg = t.orgId eq orgId
    return t.select(t.slug, t.name, t.members, t.repos)
        .where { if (after == null) inOrg else inOrg and (t.slug greater after) }
        .orderBy(t.slug).limit(limit)
}

/** A list of names stored as a JSON array in a text column. */
private object NamesColumn {
    private val serializer = ListSerializer(String.serializer())

    fun encode(names: List<String>): String = Json.encodeToString(serializer, names)

    fun decode(text: String): List<String> = Json.decodeFromString(serializer, text)
}
