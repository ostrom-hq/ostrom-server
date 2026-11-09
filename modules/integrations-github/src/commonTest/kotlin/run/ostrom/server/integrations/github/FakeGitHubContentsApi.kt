// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.domain.GitHubContentsApi
import run.ostrom.server.rules.domain.models.BranchProtection
import run.ostrom.server.rules.domain.models.RepositoryTree

/** GitHub's repository contents in memory, by `owner/name`; every read is recorded with its installation. */
class FakeGitHubContentsApi : GitHubContentsApi {
    val trees = mutableMapOf<String, RepositoryTree>()
    val files = mutableMapOf<Pair<String, String>, String>()
    val protections = mutableMapOf<String, BranchProtection>()

    /** Each read: the installation, the repository and the branch. */
    val reads = mutableListOf<Triple<Long, String, String>>()
    var failure: Exception? = null

    override suspend fun tree(installationId: Long, repo: String, branch: String): RepositoryTree {
        read(installationId, repo, branch)
        return trees[repo] ?: RepositoryTree(emptyList(), truncated = false)
    }

    override suspend fun file(installationId: Long, repo: String, branch: String, path: String): String? {
        read(installationId, repo, branch)
        return files[repo to path]
    }

    override suspend fun protection(installationId: Long, repo: String, branch: String): BranchProtection? {
        read(installationId, repo, branch)
        return protections[repo]
    }

    private fun read(installationId: Long, repo: String, branch: String) {
        failure?.let { throw it }
        reads += Triple(installationId, repo, branch)
    }
}

/** The repository acme/api as the analysis finds it: CODEOWNERS, migrations, a CI workflow and a protected main. */
fun FakeGitHubContentsApi.acmeApi() = apply {
    trees["acme/api"] = RepositoryTree(
        listOf(
            ".github/CODEOWNERS",
            ".github/workflows/ci.yml",
            "build.gradle.kts",
            "src/main/kotlin/App.kt",
            "src/main/resources/db/migration/V1__init.sql",
        ),
        truncated = false,
    )
    files["acme/api" to ".github/CODEOWNERS"] = "* @acme/platform\n/src/main/resources/db/ @acme/dba\n"
    protections["acme/api"] = BranchProtection(true, 1, true, listOf("build"), blocksForcePush = true)
}
