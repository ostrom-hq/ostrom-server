// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github

import dev.ostrom.server.integrations.github.domain.CreatedGitHubApp
import dev.ostrom.server.integrations.github.domain.DirectoryState
import dev.ostrom.server.integrations.github.domain.GitHubAppCredentials
import dev.ostrom.server.integrations.github.domain.GitHubAppStore
import dev.ostrom.server.integrations.github.domain.GitHubDirectory
import dev.ostrom.server.integrations.github.domain.GitHubDirectoryApi
import dev.ostrom.server.integrations.github.domain.GitHubDirectoryRepository
import dev.ostrom.server.integrations.github.domain.GitHubManifestApi
import dev.ostrom.server.integrations.github.domain.GitHubRepo
import dev.ostrom.server.integrations.github.domain.GitHubTeam
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** GitHub's directory as maps per installation, counting the reads. */
class FakeGitHubDirectoryApi : GitHubDirectoryApi, GitHubManifestApi {
    val repos = mutableMapOf<Long, List<GitHubRepo>>()
    val teams = mutableMapOf<Long, List<GitHubTeam>>()
    val created = mutableMapOf<String, CreatedGitHubApp>()
    var failure: Exception? = null
    var reads = 0

    /** Holds every read of the teams until completed: two lists of a new organization at once. */
    var pause: CompletableDeferred<Unit>? = null

    override suspend fun repositories(installationId: Long): List<GitHubRepo> {
        failure?.let { throw it }
        reads++
        return repos[installationId].orEmpty()
    }

    override suspend fun teams(installationId: Long, account: String): List<GitHubTeam> {
        pause?.await()
        failure?.let { throw it }
        return teams[installationId].orEmpty()
    }

    override suspend fun convertManifest(code: String): CreatedGitHubApp? {
        failure?.let { throw it }
        return created.remove(code)
    }
}

/** The stored directory in memory, with the same keyset reads. */
class MemoryGitHubDirectoryRepository : GitHubDirectoryRepository {
    val directories = mutableMapOf<Uuid, GitHubDirectory>()
    val states = mutableMapOf<Uuid, DirectoryState>()

    // False: another server claimed the read first.
    var claimable = true

    override suspend fun state(orgId: Uuid) = states[orgId]

    override suspend fun claim(orgId: Uuid, now: Instant, staleBefore: Instant): Boolean {
        val state = states[orgId]?.takeIf { claimable && it.checkedAt < staleBefore }
        state?.let { states[orgId] = it.copy(checkedAt = now) }
        return state != null
    }

    override suspend fun replace(orgId: Uuid, directory: GitHubDirectory, at: Instant) {
        directories[orgId] = directory
        states[orgId] = DirectoryState(at, at)
    }

    override suspend fun forget(orgId: Uuid) {
        states.remove(orgId)
    }

    override suspend fun repos(orgId: Uuid, after: String?, limit: Int) = directories[orgId]?.repos.orEmpty()
        .sortedBy { it.name }.filter { after == null || it.name > after }.take(limit)

    override suspend fun teams(orgId: Uuid, after: String?, limit: Int) = directories[orgId]?.teams.orEmpty()
        .sortedBy { it.slug }.filter { after == null || it.slug > after }.take(limit)
}

/** The stored App in memory: one App, single-use states. */
class MemoryGitHubAppStore(var stored: GitHubAppCredentials? = null) : GitHubAppStore {
    val states = mutableMapOf<String, Pair<String, Instant>>()
    var reads = 0

    override suspend fun app(): GitHubAppCredentials? {
        reads++
        return stored
    }

    override suspend fun saveApp(app: CreatedGitHubApp, by: String, at: Instant): Boolean {
        if (stored != null) return false
        stored = GitHubAppCredentials(
            app.id.toString(),
            app.slug,
            app.privateKeyPem,
            app.webhookSecret,
            app.clientId,
            app.clientSecret,
        )
        return true
    }

    override suspend fun saveState(stateHash: String, by: String, expiresAt: Instant) {
        states[stateHash] = by to expiresAt
    }

    override suspend fun takeState(stateHash: String, now: Instant): String? =
        states.remove(stateHash)?.takeIf { it.second > now }?.first
}

/** A repository as GitHub lists it. */
fun repo(name: String, archived: Boolean = false) = GitHubRepo(
    name = name,
    description = "The $name service",
    language = "Kotlin",
    defaultBranch = "main",
    archived = archived,
    pushedAt = Instant.parse("2026-10-29T12:00:00Z"),
)

/** An App the manifest flow created. */
fun createdApp(id: Long = 77) = CreatedGitHubApp(
    id = id,
    slug = "ostrom-acme",
    owner = "acme",
    privateKeyPem = "-----BEGIN RSA PRIVATE KEY-----\nkey\n-----END RSA PRIVATE KEY-----",
    webhookSecret = "whsec-created",
    clientId = "Iv1.abc",
    clientSecret = "client-secret",
)
