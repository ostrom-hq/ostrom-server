// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.infrastructure

import dev.ostrom.server.integrations.github.domain.DirectoryState
import dev.ostrom.server.integrations.github.domain.GitHubDirectory
import dev.ostrom.server.integrations.github.domain.GitHubDirectoryRepository
import dev.ostrom.server.integrations.github.domain.GitHubRepo
import dev.ostrom.server.integrations.github.domain.GitHubTeam
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [GitHubDirectoryRepository] on PostgreSQL. Every call is one transaction of the organization (`ostrom.org_id`), the
 * only rows row level security lets it see or write. A list is read by its primary key `(org_id, name)` or
 * `(org_id, slug)`, keyset from the cursor, bounded by its limit.
 *
 * @param driver The connection pool.
 */
class PostgresGitHubDirectoryRepository(private val driver: Driver) : GitHubDirectoryRepository {
    private suspend fun <T> scoped(orgId: Uuid, block: suspend Tx.() -> T): T = driver.transaction {
        val tx = Tx(this)
        tx.fetch(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgId.toString()))
        tx.block()
    }

    override suspend fun state(orgId: Uuid): DirectoryState? = scoped(orgId) {
        fetch(Statement.create(GitHubDirectoryQueries.STATE).bind("org", orgId)).firstOrNull()?.let {
            DirectoryState(it.get("synced_at").asInstantOrNull(), it.get("checked_at").asInstant())
        }
    }

    override suspend fun claim(orgId: Uuid, now: Instant, staleBefore: Instant): Boolean = scoped(orgId) {
        execute(
            Statement.create(
                "UPDATE github_directory_syncs SET checked_at = :now WHERE org_id = :org AND checked_at < :stale",
            ).bind("now", now).bind("org", orgId).bind("stale", staleBefore),
        ) == 1L
    }

    override suspend fun replace(orgId: Uuid, directory: GitHubDirectory, at: Instant) {
        scoped(orgId) {
            // One replacement of an organization at a time: the console lists the repositories and the teams at once,
            // and two first reads would otherwise insert the same keys side by side (a unique violation).
            val lock = Statement.create("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtext(:key))) AS l")
            fetch(lock.bind("key", "github-directory:$orgId"))
            execute(Statement.create("DELETE FROM github_repos WHERE org_id = :org").bind("org", orgId))
            directory.repos.chunked(CHUNK).forEach { execute(insertRepos(orgId, it)) }
            execute(Statement.create("DELETE FROM github_teams WHERE org_id = :org").bind("org", orgId))
            directory.teams.chunked(CHUNK).forEach { execute(insertTeams(orgId, it)) }
            execute(
                Statement.create(
                    "INSERT INTO github_directory_syncs (org_id, synced_at, checked_at) VALUES (:org, :at, :checked) " +
                        "ON CONFLICT (org_id) DO UPDATE SET synced_at = EXCLUDED.synced_at, " +
                        "checked_at = EXCLUDED.checked_at",
                ).bind("org", orgId).bind("at", at).bind("checked", at),
            )
        }
    }

    override suspend fun forget(orgId: Uuid) {
        scoped(orgId) {
            execute(Statement.create("DELETE FROM github_directory_syncs WHERE org_id = :org").bind("org", orgId))
        }
    }

    override suspend fun repos(orgId: Uuid, after: String?, limit: Int): List<GitHubRepo> = scoped(orgId) {
        fetch(keyset(GitHubDirectoryQueries.repos(after != null), orgId, after, limit)).map { it.toRepo() }
    }

    override suspend fun teams(orgId: Uuid, after: String?, limit: Int): List<GitHubTeam> = scoped(orgId) {
        fetch(keyset(GitHubDirectoryQueries.teams(after != null), orgId, after, limit)).map { it.toTeam() }
    }

    override suspend fun hasRepo(orgId: Uuid, name: String): Boolean = scoped(orgId) {
        fetch(Statement.create(GitHubDirectoryQueries.REPO).bind("org", orgId).bind("key", name)).isNotEmpty()
    }

    override suspend fun hasTeam(orgId: Uuid, slug: String): Boolean = scoped(orgId) {
        fetch(Statement.create(GitHubDirectoryQueries.TEAM).bind("org", orgId).bind("key", slug)).isNotEmpty()
    }

    private fun keyset(sql: String, orgId: Uuid, after: String?, limit: Int): Statement {
        val statement = Statement.create(sql).bind("org", orgId).bind("limit", limit)
        return if (after == null) statement else statement.bind("after", after)
    }

    // One statement per chunk; each row has its own parameters (`:o3`, `:n3`...), the organization included.
    private fun insertRepos(orgId: Uuid, repos: List<GitHubRepo>): Statement {
        val rows = repos.indices.joinToString(", ") { "(:o$it, :n$it, :d$it, :l$it, :b$it, :a$it, :p$it, :w$it)" }
        var statement = Statement.create(
            "INSERT INTO github_repos (org_id, name, description, language, default_branch, archived, pushed_at, " +
                "owners) VALUES $rows",
        )
        repos.forEachIndexed { index, repo ->
            statement = statement.bind("o$index", orgId).bind("n$index", repo.name)
                .bind("d$index", repo.description).bind("l$index", repo.language)
                .bind("b$index", repo.defaultBranch).bind("a$index", repo.archived)
                .bind("p$index", repo.pushedAt).bind("w$index", NamesColumn.encode(repo.owners))
        }
        return statement
    }

    private fun insertTeams(orgId: Uuid, teams: List<GitHubTeam>): Statement {
        val rows = teams.indices.joinToString(", ") { "(:o$it, :s$it, :n$it, :m$it, :r$it)" }
        var statement = Statement.create("INSERT INTO github_teams (org_id, slug, name, members, repos) VALUES $rows")
        teams.forEachIndexed { index, team ->
            statement = statement.bind("o$index", orgId).bind("s$index", team.slug).bind("n$index", team.name)
                .bind("m$index", NamesColumn.encode(team.members)).bind("r$index", NamesColumn.encode(team.repos))
        }
        return statement
    }

    private class Tx(private val executor: QueryExecutor) {
        suspend fun fetch(statement: Statement): List<ResultSet.Row> =
            executor.fetchAll(statement).getOrThrow().toList()

        suspend fun execute(statement: Statement): Long = executor.execute(statement).getOrThrow()
    }

    private companion object {
        // 8 parameters a row: 1 600 a statement, far under PostgreSQL's 65 535.
        const val CHUNK = 200

        fun ResultSet.Row.toRepo() = GitHubRepo(
            name = get("name").asString(),
            description = get("description").asStringOrNull(),
            language = get("language").asStringOrNull(),
            defaultBranch = get("default_branch").asString(),
            archived = get("archived").asBoolean(),
            pushedAt = get("pushed_at").asInstantOrNull(),
            owners = NamesColumn.decode(get("owners").asString()),
        )

        fun ResultSet.Row.toTeam() = GitHubTeam(
            slug = get("slug").asString(),
            name = get("name").asString(),
            members = NamesColumn.decode(get("members").asString()),
            repos = NamesColumn.decode(get("repos").asString()),
        )
    }
}

/**
 * The request-path reads of the directory, the SQL its EXPLAIN test reads: by the primary key, keyset from the cursor
 * (`:after`), bounded by `:limit`.
 */
internal object GitHubDirectoryQueries {
    const val STATE = "SELECT synced_at, checked_at FROM github_directory_syncs WHERE org_id = :org"
    private const val REPO_COLUMNS = "name, description, language, default_branch, archived, pushed_at, owners"
    private const val TEAM_COLUMNS = "slug, name, members, repos"

    /** One repository by its key, to check a cursor. */
    const val REPO = "SELECT name FROM github_repos WHERE org_id = :org AND name = :key"

    /** One team by its key, to check a cursor. */
    const val TEAM = "SELECT slug FROM github_teams WHERE org_id = :org AND slug = :key"

    fun repos(cursor: Boolean): String = keyset("$REPO_COLUMNS FROM github_repos", "name", cursor)

    fun teams(cursor: Boolean): String = keyset("$TEAM_COLUMNS FROM github_teams", "slug", cursor)

    private fun keyset(from: String, key: String, cursor: Boolean): String {
        val after = if (cursor) " AND $key > :after" else ""
        return "SELECT $from WHERE org_id = :org$after ORDER BY $key LIMIT :limit"
    }
}

/** A list of names stored as a JSON array in a text column. */
private object NamesColumn {
    private val serializer = ListSerializer(String.serializer())

    fun encode(names: List<String>): String = Json.encodeToString(serializer, names)

    fun decode(text: String): List<String> = Json.decodeFromString(serializer, text)
}
