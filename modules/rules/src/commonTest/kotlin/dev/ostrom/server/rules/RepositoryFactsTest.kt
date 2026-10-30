// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.rules.domain.services.AreaKind
import dev.ostrom.server.rules.domain.services.BuildFacts
import dev.ostrom.server.rules.domain.services.BuildTool
import dev.ostrom.server.rules.domain.services.CodeOwners
import dev.ostrom.server.rules.domain.services.LanguageShare
import dev.ostrom.server.rules.domain.services.OwnerEntry
import dev.ostrom.server.rules.domain.services.RepositoryFacts
import dev.ostrom.server.rules.domain.services.SensitiveArea
import dev.ostrom.server.rules.domain.services.SensitiveFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryFactsTest {
    @Test
    fun theAnalysisReadsOnlyTheCodeownersGitHubAppliesAndTheRootManifest() {
        assertEquals(
            listOf(".github/CODEOWNERS", "package.json"),
            RepositoryFacts.wantedFiles(listOf("CODEOWNERS", ".github/CODEOWNERS", "package.json", "web/package.json")),
        )
        assertEquals(listOf("docs/CODEOWNERS"), RepositoryFacts.wantedFiles(listOf("docs/CODEOWNERS", "a.kt")))
        assertEquals(emptyList(), RepositoryFacts.wantedFiles(listOf("README.md")))
        assertTrue(RepositoryFacts.vendored("web/node_modules/x/index.js"))
        assertFalse(RepositoryFacts.vendored("build.gradle.kts"), "A file named like a vendored folder is not one")
    }

    @Test
    fun languagesAreCountedByExtensionWithoutVendoredFiles() {
        val paths = listOf("a.kt", "b.KT", "c.ts", "d.ts", "e.py", "Makefile", "node_modules/x.js", "dist/y.ts", ".env")
        assertEquals(
            listOf(LanguageShare("Kotlin", 2), LanguageShare("TypeScript", 2), LanguageShare("Python", 1)),
            BuildFacts.languages(paths),
        )
    }

    @Test
    fun gradleAndMavenAreFoundByTheirWrapperOrTheirBuildFile() {
        assertEquals(
            listOf(BuildTool("Gradle", "./gradlew check", listOf("gradlew", "settings.gradle"), true)),
            BuildFacts.buildTools(listOf("gradlew", "settings.gradle"), null),
        )
        assertEquals(
            listOf(BuildTool("Gradle", "./gradlew check", listOf("gradlew"), true)),
            BuildFacts.buildTools(listOf("gradlew"), null),
        )
        assertEquals(
            listOf(BuildTool("Gradle", "gradle check", listOf("build.gradle"), false)),
            BuildFacts.buildTools(listOf("build.gradle", "app/build.gradle.kts"), null),
        )
        assertEquals(
            listOf(BuildTool("Maven", "./mvnw verify", listOf("mvnw", "pom.xml"), true)),
            BuildFacts.buildTools(listOf("mvnw", "pom.xml"), null),
        )
        assertEquals(
            listOf(BuildTool("Maven", "mvn verify", listOf("pom.xml"), false)),
            BuildFacts.buildTools(listOf("pom.xml"), null),
        )
    }

    @Test
    fun aNodeRepositoryChecksWithItsLockfilesManagerAndItsOwnScripts() {
        val all = """{"scripts":{"lint":"x","typecheck":"y","test":"z","build":"w"}}"""
        fun tool(lockfile: String?, manifest: String?) =
            BuildFacts.buildTools(listOfNotNull("package.json", lockfile), manifest).single()
        assertEquals(
            BuildTool(
                "pnpm",
                "pnpm lint && pnpm typecheck && pnpm test",
                listOf("pnpm-lock.yaml", "package.json"),
                true,
            ),
            tool("pnpm-lock.yaml", all),
        )
        assertEquals("yarn lint && yarn typecheck && yarn test", tool("yarn.lock", all).command)
        assertEquals("bun run lint && bun run typecheck && bun run test", tool("bun.lock", all).command)
        assertEquals("Bun", tool("bun.lockb", all).name)
        assertEquals(
            BuildTool(
                "npm",
                "npm run lint && npm run typecheck && npm test",
                listOf("package-lock.json", "package.json"),
                true,
            ),
            tool("package-lock.json", all),
        )
        assertEquals(BuildTool("npm", null, listOf("package.json"), false), tool(null, null), "Unread manifest")
        assertEquals(null, tool(null, "not json").command, "A manifest that is not JSON names no script")
        assertEquals(null, tool(null, "[1]").command, "Nor one that is not an object")
        assertEquals(null, tool(null, """{"scripts":"lint"}""").command, "Nor scripts that are not an object")
        assertEquals(null, tool(null, """{"scripts":{"build":"vite"}}""").command, "Nor a manifest with no check")
    }

    @Test
    fun cargoGoAndPythonAreFoundWithTheirLockfiles() {
        assertEquals(
            listOf(BuildTool("Cargo", "cargo test", listOf("Cargo.lock", "Cargo.toml"), true)),
            BuildFacts.buildTools(listOf("Cargo.toml", "Cargo.lock"), null),
        )
        assertEquals(
            listOf(BuildTool("Cargo", "cargo test", listOf("Cargo.toml"), false)),
            BuildFacts.buildTools(listOf("Cargo.toml"), null),
        )
        assertEquals(
            listOf(BuildTool("Go", "go test ./...", listOf("go.sum", "go.mod"), true)),
            BuildFacts.buildTools(listOf("go.mod", "go.sum"), null),
        )
        assertEquals(
            listOf(BuildTool("Poetry", "poetry run pytest", listOf("poetry.lock", "pyproject.toml"), true)),
            BuildFacts.buildTools(listOf("pyproject.toml", "poetry.lock", "pkg/test_a.py"), null),
        )
        assertEquals(
            listOf(BuildTool("pip", null, listOf("requirements.txt"), false)),
            BuildFacts.buildTools(listOf("requirements.txt", "app.py"), null),
            "No test found: no command",
        )
        assertEquals(
            listOf(BuildTool("pip", "python -m pytest", listOf("setup.py"), false)),
            BuildFacts.buildTools(listOf("setup.py", "tests/a.py"), null),
        )
    }

    @Test
    fun makeIsOnlyNamedWhenNothingElseBuilds() {
        assertEquals(
            listOf(BuildTool("Make", null, listOf("Makefile"), false)),
            BuildFacts.buildTools(listOf("Makefile"), null),
        )
        assertEquals(listOf("Go"), BuildFacts.buildTools(listOf("Makefile", "go.mod"), null).map { it.name })
        assertEquals(emptyList(), BuildFacts.buildTools(listOf("README.md", "src/Makefile"), null))
    }

    @Test
    fun secretsAreFilesThatHoldKeysOrEnvironmentsButNotTheirSamples() {
        val paths = listOf(
            ".env", "app/.env.local", ".env.example", ".env.sample", "keys/id_rsa", "keys/id_rsa.pub", "certs/tls.KEY",
            "android/release.jks", "credentials.json", "node_modules/x/.env",
        )
        assertEquals(
            listOf(
                SensitiveArea(
                    AreaKind.SECRETS,
                    listOf(".env", "android/release.jks", "app/.env.local", "certs/tls.KEY", "credentials.json"),
                    listOf(".env", "android/release.jks", "app/.env.local", "certs/tls.KEY", "credentials.json"),
                ),
            ),
            SensitiveFiles.areas(paths).map { it.copy(prefixes = it.prefixes.take(5)) },
        )
        assertEquals(6, SensitiveFiles.areas(paths).single().prefixes.size, "keys/id_rsa too; its .pub is public")
    }

    @Test
    fun ciInfrastructureAndMigrationsAreTheShortestFoldersThatHoldThem() {
        val paths = listOf(
            ".github/workflows/a.yml", ".github/workflows/b.yml", ".github/dependabot.yml", ".circleci/config.yml",
            "Jenkinsfile", "ops/Jenkinsfile", "ops/main.tf", "ops/helm/values.yaml", "deploy/k8s/app.yaml",
            "src/db/migrations/1.sql", "sql/V1_2__users.sql", "db/migrate/2.rb", "src/migration.kt",
        )
        val areas = SensitiveFiles.areas(paths).associate { it.kind to it.prefixes }
        assertEquals(listOf(".circleci/", ".github/workflows/", "Jenkinsfile"), areas[AreaKind.CI])
        assertEquals(listOf("deploy/k8s/", "ops/"), areas[AreaKind.INFRA], "ops/ covers ops/helm/")
        assertEquals(listOf("db/migrate/", "sql/", "src/db/migrations/"), areas[AreaKind.MIGRATIONS])
        assertEquals(null, areas[AreaKind.SECRETS])
    }

    @Test
    fun anAreaKeepsTenFoldersAndFiveFilesAtMost() {
        val paths = (1..12).map { "svc$it/migrations/1.sql" }
        val area = SensitiveFiles.areas(paths).single()
        assertEquals(10, area.prefixes.size)
        assertEquals(5, area.evidence.size)
        assertEquals("svc1/migrations/1.sql", area.evidence.first())
    }

    @Test
    fun codeownersIsReadLineByLineWithoutItsComments() {
        val text = """
            # comment
            *  @lyes

            /docs/   @mia @acme/docs   # docs
            *.sql	@dba@acme.dev	# tabbed
            /vendor/
        """.trimIndent()
        assertEquals(
            listOf(
                OwnerEntry("*", listOf("@lyes")),
                OwnerEntry("/docs/", listOf("@mia", "@acme/docs")),
                OwnerEntry("*.sql", listOf("@dba@acme.dev")),
                OwnerEntry("/vendor/", emptyList()),
            ),
            CodeOwners.parse(text),
        )
        assertEquals(50, CodeOwners.parse((1..60).joinToString("\n") { "/d$it/ @x" }).size)
    }

    @Test
    fun theLastMatchingEntryNamesTheOwners() {
        val entries = CodeOwners.parse("* @lyes\n/docs/ @mia\n*.sql @dba\nsrc/**/test/ @qa\n/a?c.txt @one\nbin/** @ops")
        assertEquals(listOf("@lyes"), CodeOwners.ownersOf(entries, "README.md"))
        assertEquals(listOf("@mia"), CodeOwners.ownersOf(entries, "docs/guide.md"))
        assertEquals(listOf("@lyes"), CodeOwners.ownersOf(entries, "api/docs/guide.md"), "/docs/ is anchored")
        assertEquals(listOf("@dba"), CodeOwners.ownersOf(entries, "docs/schema.sql"))
        assertEquals(listOf("@qa"), CodeOwners.ownersOf(entries, "src/test/a.kt"))
        assertEquals(listOf("@qa"), CodeOwners.ownersOf(entries, "src/a/b/test/a.kt"))
        assertEquals(listOf("@one"), CodeOwners.ownersOf(entries, "abc.txt"))
        assertEquals(listOf("@ops"), CodeOwners.ownersOf(entries, "bin/x/y"))
        assertEquals(emptyList(), CodeOwners.ownersOf(emptyList(), "README.md"))
        assertEquals(
            listOf("lyes", "mia"),
            CodeOwners.logins(listOf("@lyes", "@acme/core", "me@acme.dev", "@mia", "@lyes")),
        )
    }

    @Test
    fun gatePatternsMatchTheAbsolutePathsAnAgentsToolsName() {
        assertEquals("(^|/)\\.github/workflows/", CodeOwners.prefixPattern(".github/workflows/"))
        assertEquals("(^|/)config/prod\\.pem$", CodeOwners.prefixPattern("config/prod.pem"))
        assertEquals("(^|/)docs/.*$", CodeOwners.gatePattern("/docs/"))
        assertEquals("(^|/)[^/]*\\.sql(?:/.*)?$", CodeOwners.gatePattern("*.sql"))
        assertEquals("a\\+b\\(c\\)\\[d\\]\\{e\\}\\|f\\^g\\\$h\\\\i", CodeOwners.literal("a+b(c)[d]{e}|f^g\$h\\i"))
        assertTrue(Regex(CodeOwners.gatePattern("/docs/")).containsMatchIn("/home/me/api/docs/a.md"))
        assertFalse(Regex(CodeOwners.prefixPattern("config/prod.pem")).containsMatchIn("/x/config/prod.pem.bak"))
    }
}
