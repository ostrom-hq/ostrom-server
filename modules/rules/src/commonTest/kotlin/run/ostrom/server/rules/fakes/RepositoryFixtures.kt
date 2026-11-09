// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.fakes

import run.ostrom.server.rules.domain.models.BranchProtection
import run.ostrom.server.rules.domain.models.RepositoryListing
import run.ostrom.server.rules.domain.models.RepositoryReadException
import run.ostrom.server.rules.domain.models.RepositoryRef
import run.ostrom.server.rules.domain.models.RepositorySnapshot
import run.ostrom.server.rules.domain.models.RepositoryTree
import run.ostrom.server.rules.domain.services.RepositoryReader
import kotlin.uuid.Uuid

/**
 * A repository as GitHub would show it to the analysis: its files, the few contents the analysis reads, and its
 * default branch's protection.
 *
 * @property repo The repository.
 * @property paths Its files.
 * @property contents The texts of some of them.
 * @property protection Its protection, or null when unreadable.
 * @property truncated True when GitHub cut its file list.
 */
data class RepositoryFixture(
    val repo: RepositoryRef,
    val paths: List<String>,
    val contents: Map<String, String> = emptyMap(),
    val protection: BranchProtection? = null,
    val truncated: Boolean = false,
) {
    /** What the analysis reads of it. */
    val snapshot: RepositorySnapshot
        get() = RepositorySnapshot(
            repo,
            RepositoryTree(paths, truncated),
            contents.filterKeys { it in setOf(".github/CODEOWNERS", "CODEOWNERS", "docs/CODEOWNERS", "package.json") },
            protection,
        )
}

/** The sample repositories of the analysis tests. */
object RepositoryFixtures {
    /**
     * A repository reference.
     *
     * @param name Its name.
     * @param branch Its default branch.
     * @param pushedAt Its last push.
     * @param archived Whether GitHub archived it.
     */
    fun ref(
        name: String,
        branch: String = "main",
        pushedAt: String? = "2026-10-29T10:00:00Z",
        archived: Boolean = false,
    ) =
        RepositoryRef("acme", name, branch, archived, "The $name service", pushedAt, "https://github.com/acme/$name")

    /** A Kotlin service on Gradle with migrations, a Helm chart, a key on the branch, CODEOWNERS, protected main. */
    val api = RepositoryFixture(
        repo = ref("api"),
        paths = listOf(
            "gradlew", "build.gradle.kts", "settings.gradle.kts", "README.md",
            "src/main/kotlin/App.kt", "src/main/kotlin/Routes.kt", "src/test/kotlin/AppTest.kt",
            "src/main/resources/db/migration/V1__init.sql", "src/main/resources/db/migration/V2__users.sql",
            ".github/workflows/ci.yml", ".github/workflows/deploy.yml", ".github/CODEOWNERS",
            "helm/api/Chart.yaml", "helm/api/values.yaml", "config/prod.pem", "build/generated/Gen.kt",
        ),
        contents = mapOf(
            ".github/CODEOWNERS" to """
                # Owners of the api
                *       @lyes
                /src/main/resources/db/  @mia @acme/dba
                .github/ @lyes   # the CI
                /vendor/
            """.trimIndent(),
        ),
        protection = BranchProtection(
            protected = true,
            requiredApprovals = 1,
            codeOwnerReviews = true,
            requiredChecks = listOf("ci"),
            blocksForcePush = true,
        ),
    )

    /** A TypeScript app on pnpm with a committed `.env`, Terraform, Prisma migrations, GitLab CI, unprotected. */
    val web = RepositoryFixture(
        repo = ref("web", branch = "develop", pushedAt = "2026-10-30T09:00:00Z"),
        paths = listOf(
            "package.json", "pnpm-lock.yaml", "src/index.ts", "src/app.tsx", "src/util.js", ".env", ".env.example",
            "terraform/main.tf", "prisma/migrations/001/migration.sql", "node_modules/left-pad/index.js",
            ".gitlab-ci.yml",
        ),
        contents = mapOf("package.json" to """{"scripts":{"lint":"eslint .","test":"vitest","build":"vite build"}}"""),
        protection = BranchProtection(
            protected = false,
            requiredApprovals = 0,
            codeOwnerReviews = false,
            requiredChecks = emptyList(),
            blocksForcePush = false,
        ),
    )

    /** A Python tool on uv with Django migrations, a cut file list and an unreadable protection. */
    val tools = RepositoryFixture(
        repo = ref("tools", pushedAt = null).copy(description = null),
        paths = listOf(
            "pyproject.toml",
            "uv.lock",
            "tests/test_cli.py",
            "tools/cli.py",
            "app/migrations/0001_initial.py",
        ),
        truncated = true,
    )

    /** Documentation with a Makefile and no code. */
    val docs = RepositoryFixture(
        repo = ref("docs", pushedAt = "2026-10-01T00:00:00Z"),
        paths = listOf("README.md", "Makefile"),
    )
}

/**
 * GitHub for the analysis, in memory: one organization connected per listing, its repositories read from fixtures.
 *
 * @property listings What each organization's installation lists; an organization absent is not connected.
 * @property fixtures The repositories, by name.
 */
class FakeRepositoryReader(
    val listings: MutableMap<Uuid, RepositoryListing> = mutableMapOf(),
    val fixtures: MutableMap<String, RepositoryFixture> = mutableMapOf(),
) : RepositoryReader {
    val trees = mutableListOf<String>()
    val files = mutableListOf<String>()
    var failing: Set<String> = emptySet()
    var listingFailure: RepositoryReadException? = null
    var onTree: suspend (RepositoryRef) -> Unit = {}

    /**
     * Connects an organization to these fixtures.
     *
     * @param orgId The organization.
     * @param repos Its repositories.
     */
    fun connect(orgId: Uuid, vararg repos: RepositoryFixture) {
        repos.forEach { fixtures[it.repo.name] = it }
        listings[orgId] = RepositoryListing.Connected(repos.map { it.repo })
    }

    override suspend fun repositories(orgId: Uuid): RepositoryListing {
        listingFailure?.let { throw it }
        return listings[orgId] ?: RepositoryListing.NotConnected("Install the GitHub App.")
    }

    override suspend fun tree(orgId: Uuid, repo: RepositoryRef): RepositoryTree {
        trees += repo.name
        onTree(repo)
        if (repo.name in failing) throw RepositoryReadException("GitHub refused to list ${repo.name}.")
        val fixture = fixtures.getValue(repo.name)
        return RepositoryTree(fixture.paths, fixture.truncated)
    }

    override suspend fun file(orgId: Uuid, repo: RepositoryRef, path: String): String? {
        files += "${repo.name}/$path"
        return fixtures.getValue(repo.name).contents[path]
    }

    override suspend fun protection(orgId: Uuid, repo: RepositoryRef): BranchProtection? =
        fixtures.getValue(repo.name).protection
}
