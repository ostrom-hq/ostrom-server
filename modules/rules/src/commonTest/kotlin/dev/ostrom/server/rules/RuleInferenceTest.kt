// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.rules.domain.models.BranchProtection
import dev.ostrom.server.rules.domain.models.RuleGate
import dev.ostrom.server.rules.domain.models.RuleGateAction
import dev.ostrom.server.rules.domain.models.RuleProposal
import dev.ostrom.server.rules.domain.models.RuleSource
import dev.ostrom.server.rules.domain.models.RuleStrength
import dev.ostrom.server.rules.domain.services.RuleInference
import dev.ostrom.server.rules.fakes.RepositoryFixture
import dev.ostrom.server.rules.fakes.RepositoryFixtures
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleInferenceTest {
    private val edit = listOf("Write", "Edit", "MultiEdit")

    private fun infer(fixture: RepositoryFixture) = RuleInference.infer(fixture.snapshot).associateBy { it.key }

    private fun file(repo: String, path: String, kind: String = "file", branch: String = "main") =
        RuleSource(kind, path, "https://github.com/acme/$repo/blob/$branch/$path")

    @Test
    fun aGradleServiceIsCheckedWithItsWrapper() {
        val rules = infer(RepositoryFixtures.api)
        assertEquals(
            listOf(
                "build:gradle:api", "area:api:secrets", "area:api:ci", "area:api:infra", "area:api:migrations",
                "owners:*:api", "owners:/src/main/resources/db/:api", "owners:.github/:api", "branch:api",
            ),
            rules.keys.toList(),
        )
        assertEquals(
            RuleProposal(
                key = "build:gradle:api",
                statement = "In api, check a change with `./gradlew check` before pushing it.",
                output = "check",
                group = "Build",
                scope = listOf("api"),
                sources = listOf(file("api", "gradlew"), file("api", "build.gradle.kts")),
                why = "Found `gradlew`, `build.gradle.kts` at the root, which pins the tool. " +
                    "Code: Kotlin 71 %, SQL 29 % of 7 files.",
                strength = RuleStrength.RECOMMENDED,
                confidence = 0.9,
                gate = null,
            ),
            rules["build:gradle:api"],
        )
    }

    @Test
    fun sensitiveFilesAreGatedAndTheirCodeOwnersApprove() {
        val rules = infer(RepositoryFixtures.api)
        assertEquals(
            RuleProposal(
                key = "area:api:secrets",
                statement = "Agents never read, change or print the secret files of api (`config/prod.pem`).",
                output = "gate",
                group = "Secrets",
                scope = listOf("api/config/prod.pem"),
                sources = listOf(file("api", "config/prod.pem")),
                why = "These files are on `main`: a secret in git reaches everyone who can clone the repository. " +
                    "Move them to a secret store and rotate what they hold.",
                strength = RuleStrength.REQUIRED,
                confidence = 0.9,
                gate = RuleGate(
                    actions = listOf(
                        RuleGateAction(
                            "secret-files",
                            "Read or change a secret file",
                            listOf("(^|/)config/prod\\.pem$"),
                            listOf("api"),
                            listOf("Read") + edit,
                        ),
                    ),
                    approvers = listOf("lyes"),
                    reason = "code owners in .github/CODEOWNERS",
                ),
            ),
            rules["area:api:secrets"],
        )
        val ci = rules.getValue("area:api:ci")
        assertEquals("A change to the CI of api (`.github/workflows/`) needs a yes from @lyes.", ci.statement)
        assertEquals("The CI runs with the repository's secrets and decides what reaches `main`.", ci.why)
        assertEquals(0.85, ci.confidence)
        assertEquals(
            listOf(file("api", ".github/workflows/ci.yml", "ci"), file("api", ".github/workflows/deploy.yml", "ci")),
            ci.sources,
        )
        assertEquals(
            RuleGateAction("edit-ci", "Change the CI", listOf("(^|/)\\.github/workflows/"), listOf("api"), edit),
            ci.gate?.actions?.single(),
        )
        val infra = rules.getValue("area:api:infra")
        assertEquals("A change to the infrastructure of api (`helm/`) needs a yes from @lyes.", infra.statement)
        assertEquals("These files describe what runs in production.", infra.why)
        assertEquals(listOf("api/helm/"), infra.scope)
        val migrations = rules.getValue("area:api:migrations")
        assertEquals(
            "In api, never edit a migration that already ran (`src/main/resources/db/migration/`): add a new one, " +
                "and a new one needs a yes from @mia.",
            migrations.statement,
        )
        assertEquals(
            "A migration that already ran is not run again: editing it leaves the databases behind the code.",
            migrations.why,
        )
        assertEquals(listOf("mia"), migrations.gate?.approvers, "A team (@acme/dba) is not a login")
        assertEquals("migration", migrations.gate?.actions?.single()?.cls)
    }

    @Test
    fun codeownersDirectoriesUnderARequiredReviewBecomeGatedDirectories() {
        val rules = infer(RepositoryFixtures.api)
        val codeowners = file("api", ".github/CODEOWNERS")
        assertEquals(
            RuleProposal(
                key = "owners:*:api",
                statement = "Every change to api is reviewed by @lyes.",
                output = "rule",
                group = "Reviews",
                scope = listOf("api"),
                sources = listOf(codeowners),
                why = "CODEOWNERS names them for every file.",
                strength = RuleStrength.REQUIRED,
                confidence = 0.9,
                gate = null,
            ),
            rules["owners:*:api"],
        )
        assertEquals(
            RuleProposal(
                key = "owners:/src/main/resources/db/:api",
                statement = "A change to `/src/main/resources/db/` in api needs a yes from its code owners " +
                    "(@mia, @acme/dba).",
                output = "gate",
                group = "Reviews",
                scope = listOf("api"),
                sources = listOf(codeowners),
                why = "CODEOWNERS names them and the protection of `main` requires their review.",
                strength = RuleStrength.REQUIRED,
                confidence = 0.9,
                gate = RuleGate(
                    actions = listOf(
                        RuleGateAction(
                            "edit-owned-src-main-resources-db",
                            "Change `/src/main/resources/db/`",
                            listOf("(^|/)src/main/resources/db/.*$"),
                            listOf("api"),
                            edit,
                        ),
                    ),
                    approvers = listOf("mia"),
                    reason = "code owners in .github/CODEOWNERS",
                ),
            ),
            rules["owners:/src/main/resources/db/:api"],
        )
        val github = rules.getValue("owners:.github/:api").gate?.actions?.single()
        assertEquals("edit-owned-github", github?.cls)
        assertEquals(listOf("(^|/)\\.github/.*$"), github?.patterns)
    }

    @Test
    fun aProtectedBranchIsNeverPushedTo() {
        assertEquals(
            RuleProposal(
                key = "branch:api",
                statement = "Never push to `main` of api: open a pull request, which merges after 1 approval and " +
                    "the checks `ci` pass.",
                output = "gate",
                group = "Branches",
                scope = listOf("api"),
                sources = listOf(
                    RuleSource("settings", "branches/main", "https://github.com/acme/api/settings/branches"),
                ),
                why = "GitHub protects `main`: 1 approval required, code owners must approve, 1 required check, " +
                    "force-push refused.",
                strength = RuleStrength.REQUIRED,
                confidence = 0.95,
                gate = RuleGate(
                    actions = listOf(
                        RuleGateAction("push-main", "Push to the main branch", emptyList(), listOf("api"), null),
                    ),
                    approvers = listOf("lyes"),
                    reason = "default code owners in .github/CODEOWNERS",
                ),
            ),
            infer(RepositoryFixtures.api)["branch:api"],
        )
        val bare = BranchProtection(true, 0, false, emptyList(), false)
        val minimal = infer(RepositoryFixtures.docs.copy(protection = bare)).getValue("branch:docs")
        assertEquals("Never push to `main` of docs: open a pull request.", minimal.statement)
        assertEquals("GitHub protects `main`: no approval required.", minimal.why)
        val two = infer(
            RepositoryFixtures.docs.copy(protection = BranchProtection(true, 2, false, listOf("a", "b"), false)),
        )
        assertEquals(
            "Never push to `main` of docs: open a pull request, which merges after 2 approvals and the checks `a`, " +
                "`b` pass.",
            two.getValue("branch:docs").statement,
        )
        assertEquals(
            "GitHub protects `main`: 2 approvals required, 2 required checks.",
            two.getValue("branch:docs").why,
        )
        val checksOnly = BranchProtection(true, 0, false, listOf("ci"), false)
        assertEquals(
            "Never push to `main` of docs: open a pull request, which merges after the checks `ci` pass.",
            infer(RepositoryFixtures.docs.copy(protection = checksOnly)).getValue("branch:docs").statement,
        )
    }

    @Test
    fun aNodeAppOnAnUnprotectedBranchGetsItsGatesWithoutApprovers() {
        val rules = infer(RepositoryFixtures.web)
        assertEquals(
            listOf(
                "build:pnpm:web",
                "area:web:secrets",
                "area:web:ci",
                "area:web:infra",
                "area:web:migrations",
                "branch:web",
            ),
            rules.keys.toList(),
        )
        val build = rules.getValue("build:pnpm:web")
        assertEquals("In web, check a change with `pnpm lint && pnpm test` before pushing it.", build.statement)
        assertEquals(
            "Found `pnpm-lock.yaml`, `package.json` at the root, which pins the tool. " +
                "Code: TypeScript 40 %, HCL 20 %, JavaScript 20 % of 5 files.",
            build.why,
        )
        assertEquals(
            listOf(file("web", "pnpm-lock.yaml", branch = "develop"), file("web", "package.json", branch = "develop")),
            build.sources,
        )
        assertEquals(
            "Agents never read, change or print the secret files of web (`.env`).",
            rules.getValue("area:web:secrets").statement,
        )
        val ci = rules.getValue("area:web:ci")
        assertEquals("A change to the CI of web (`.gitlab-ci.yml`) needs a yes.", ci.statement)
        assertEquals(emptyList(), ci.gate?.approvers, "No CODEOWNERS: no approver")
        assertEquals(null, ci.gate?.reason, "Nor a reason")
        assertEquals(listOf("(^|/)\\.gitlab-ci\\.yml$"), ci.gate?.actions?.single()?.patterns)
        assertEquals(
            "A change to the infrastructure of web (`terraform/`) needs a yes.",
            rules.getValue("area:web:infra").statement,
        )
        assertEquals(
            "In web, never edit a migration that already ran (`prisma/migrations/`): add a new one, and a new one " +
                "needs a yes.",
            rules.getValue("area:web:migrations").statement,
        )
    }

    @Test
    fun anUnprotectedDefaultBranchOtherThanMainGetsItsOwnPushPattern() {
        val branch = infer(RepositoryFixtures.web).getValue("branch:web")
        assertEquals("A push to `develop` of web needs a yes.", branch.statement)
        assertEquals(
            "GitHub does not protect `develop`: anyone with write access, an agent included, can push to it.",
            branch.why,
        )
        assertEquals(RuleStrength.RECOMMENDED, branch.strength)
        assertEquals(0.6, branch.confidence)
        assertEquals(
            RuleGate(
                listOf(
                    RuleGateAction(
                        "push-main",
                        "Push to the main branch",
                        listOf("\\bgit\\s+push\\b[^\\n;&|]*\\bdevelop\\b"),
                        listOf("web"),
                        null,
                    ),
                ),
                emptyList(),
                null,
            ),
            branch.gate,
        )
    }

    @Test
    fun aCutFileListAndAnUnreadableProtectionAreSaid() {
        val rules = infer(RepositoryFixtures.tools)
        assertEquals(listOf("build:uv:tools", "area:tools:migrations", "branch:tools"), rules.keys.toList())
        assertEquals(
            "Found `uv.lock`, `pyproject.toml` at the root, which pins the tool. Code: Python 100 % of 3 files." +
                RuleInference.TRUNCATED,
            rules.getValue("build:uv:tools").why,
        )
        assertEquals(
            "In tools, check a change with `uv run pytest` before pushing it.",
            rules.getValue("build:uv:tools").statement,
        )
        val branch = rules.getValue("branch:tools")
        assertEquals(
            "Ostrom could not read how GitHub protects `main`: a yes before a push to it is the safe default." +
                RuleInference.TRUNCATED,
            branch.why,
        )
        assertEquals(0.5, branch.confidence)
    }

    @Test
    fun aRepositoryWithoutCodeOrBuildToolSaysWhatItHas() {
        val docs = infer(RepositoryFixtures.docs).getValue("build:make:docs")
        assertEquals("In docs, build with Make.", docs.statement)
        assertEquals("rule", docs.output)
        assertEquals("Found `Makefile` at the root.", docs.why)
        assertEquals(0.7, docs.confidence)

        val notes = RepositoryFixture(RepositoryFixtures.ref("notes"), listOf("a.py", "b.py", "c.sh"))
        val languages = infer(notes).getValue("languages:notes")
        assertEquals("notes is written mostly in Python.", languages.statement)
        assertEquals("note", languages.output)
        assertEquals("Code: Python 67 %, Shell 33 % of 3 files.", languages.why)
        assertEquals(0.6, languages.confidence)
        assertEquals(listOf("branch:notes"), infer(notes.copy(paths = listOf("README.md"))).keys.toList())
    }

    @Test
    fun ownersRulesFollowWhetherTheBranchRequiresTheirReview() {
        val text = "* @acme/core\n/docs/ @mia\n/ @root\n"
        val unprotected = RepositoryFixture(
            RepositoryFixtures.ref("site"),
            listOf("CODEOWNERS", "docs/a.md"),
            mapOf("CODEOWNERS" to text),
        )
        val rules = infer(unprotected)
        val docs = rules.getValue("owners:/docs/:site")
        assertEquals("In site, `/docs/` is reviewed by @mia.", docs.statement)
        assertEquals(
            "CODEOWNERS names them: GitHub asks for their review on each pull request that touches it.",
            docs.why,
        )
        assertEquals(RuleStrength.RECOMMENDED, docs.strength)
        assertEquals(0.85, docs.confidence)
        assertEquals(null, docs.gate)
        assertEquals(null, rules.getValue("branch:site").gate?.reason, "A team owns everything: no login, no reason")
        assertEquals(emptyList(), rules.getValue("branch:site").gate?.approvers)

        val required = unprotected.copy(protection = BranchProtection(true, 1, true, emptyList(), false))
        assertEquals("edit-owned-root", infer(required).getValue("owners:/:site").gate?.actions?.single()?.cls)
    }
}
