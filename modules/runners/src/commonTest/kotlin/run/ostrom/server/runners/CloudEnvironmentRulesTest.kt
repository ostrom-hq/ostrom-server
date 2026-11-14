// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.CloudEnvironmentRules
import run.ostrom.server.runners.domain.CloudEnvironmentSpec
import run.ostrom.server.runners.domain.CloudRepository
import run.ostrom.server.runners.domain.CloudSetup
import run.ostrom.server.runners.domain.GitIdentity
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.PreparationFailure
import run.ostrom.server.runners.domain.PreparationFailures
import run.ostrom.server.runners.domain.PreparationPhase
import run.ostrom.server.runners.domain.PreparationStep
import run.ostrom.server.runners.domain.PreparedEnvironment
import run.ostrom.server.runners.domain.RepositorySpec
import run.ostrom.server.runners.domain.RunnerGitHubToken
import run.ostrom.server.runners.domain.RunnerVariableValues
import run.ostrom.server.runners.fakes.session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A session's preparation recipe, as TeamCode's `CloudEnvironmentRules`. */
class CloudEnvironmentRulesTest {
    private val repo = CloudRepository("https://github.com/acme/api", "api", ref = "main", branch = "task-1")

    private fun refused(spec: CloudEnvironmentSpec): String =
        assertFailsWith<InvalidCloudSessionRequestException> { CloudEnvironmentRules.validate(spec) }.message.orEmpty()

    @Test
    fun aFullRecipeWithinEveryBoundIsAccepted() {
        val spec = CloudEnvironmentSpec(
            repositories = listOf(repo, CloudRepository("https://github.com/acme/web.git", "apps/web")),
            workingDirectory = "api/",
            setup = CloudSetup(create = listOf("npm ci"), start = List(16) { "echo $it" }),
            requiredVariables = listOf("NPM_TOKEN", "_X1"),
            requireGitHub = true,
            gitEmailDomain = "acme.dev",
        )
        CloudEnvironmentRules.validate(spec)
        CloudEnvironmentRules.validate(CloudEnvironmentSpec())
    }

    @Test
    fun repositoriesMustBeGitHubUrlsInTheirOwnDirectories() {
        assertTrue("16" in refused(CloudEnvironmentSpec(repositories = List(17) { repo.copy(path = "r$it") })))
        for (url in listOf(
            "http://github.com/acme/api",
            "https://gitlab.com/acme/api",
            "https://user:pass@github.com/acme/api",
            "https://github.com/acme/api?x=1",
            "https://github.com/acme",
            "https://github.com/-acme/api",
        )) {
            assertTrue("clone URL" in refused(CloudEnvironmentSpec(repositories = listOf(repo.copy(url = url)))), url)
        }
        val nested = listOf(repo, repo.copy(path = "api/inner"))
        assertTrue("own directory" in refused(CloudEnvironmentSpec(repositories = nested)))
        val twice = listOf(repo, repo.copy(path = "api/"))
        assertTrue("own directory" in refused(CloudEnvironmentSpec(repositories = twice)))
    }

    @Test
    fun pathsStayBelowTheWorkspaceAndOutsideTheRunnersDirectories() {
        for (path in listOf(
            "/abs",
            "../up",
            "a/../b",
            "./a",
            ".ostrom",
            ".home/x",
            "a b",
            "-x",
            List(9) { "s" }.joinToString("/"),
            "a".repeat(256),
        )) {
            assertFailsWith<InvalidCloudSessionRequestException>(path) { CloudEnvironmentRules.validatePath(path) }
        }
        CloudEnvironmentRules.validatePath("config/app_1.x")
        assertTrue("relative path" in refused(CloudEnvironmentSpec(workingDirectory = "/etc")))
    }

    @Test
    fun refsThatLookLikeOptionsOrAreMalformedAreRefused() {
        for (ref in listOf("-c", "/main", "main/", "a.lock", "a..b", "a//b", "a b", "")) {
            val withRef = CloudEnvironmentSpec(repositories = listOf(repo.copy(ref = ref)))
            assertTrue("valid branch" in refused(withRef), ref)
            val withBranch = CloudEnvironmentSpec(repositories = listOf(repo.copy(branch = ref)))
            assertTrue("valid branch" in refused(withBranch), ref)
        }
    }

    @Test
    fun setupCommandsVariablesAndTheEmailDomainAreBounded() {
        val tooMany = CloudEnvironmentSpec(setup = CloudSetup(create = List(17) { "x" }))
        assertTrue("setup commands" in refused(tooMany))
        for (command in listOf(" ", "x".repeat(4097), "a\u0000b")) {
            assertTrue("setup commands" in refused(CloudEnvironmentSpec(setup = CloudSetup(start = listOf(command)))))
        }
        for (names in listOf(List(65) { "V$it" }, listOf("lower"), listOf("A", "A"))) {
            assertTrue("required variables" in refused(CloudEnvironmentSpec(requiredVariables = names)), "$names")
        }
        assertTrue("email domain" in refused(CloudEnvironmentSpec(gitEmailDomain = "Not A Domain")))
    }

    @Test
    fun theEffectiveRecipeTakesTheLegacyRepositoryAndTheFirstDirectory() {
        val bare = session()
        assertEquals(CloudEnvironmentSpec(), CloudEnvironmentRules.effective(bare))
        val legacy = bare.copy(repository = RepositorySpec("https://github.com/acme/api.git", "v1"))
        assertEquals(
            CloudEnvironmentSpec(
                repositories = listOf(CloudRepository("https://github.com/acme/api.git", "api", "v1")),
                workingDirectory = "api",
            ),
            CloudEnvironmentRules.effective(legacy),
        )
        val recipe = CloudEnvironmentSpec(repositories = listOf(repo.copy(path = "svc")), workingDirectory = "svc/x")
        assertEquals(recipe, CloudEnvironmentRules.effective(legacy.copy(environment = recipe)))
        val noDirectory = recipe.copy(workingDirectory = null)
        assertEquals("svc", CloudEnvironmentRules.effective(bare.copy(environment = noDirectory)).workingDirectory)
        val setupOnly = CloudEnvironmentSpec(setup = CloudSetup(start = listOf("make")))
        assertEquals(setupOnly, CloudEnvironmentRules.effective(bare.copy(environment = setupOnly)))
    }

    @Test
    fun aLinkedOwnerCommitsWithTheNoReplyAddressAndAnotherWithTheirLogin() {
        val spec = CloudEnvironmentSpec()
        val linked = RunnerGitHubToken.Linked("t", null, "octo", 42)
        assertEquals(
            GitIdentity("octo", "42+octo@users.noreply.github.com"),
            CloudEnvironmentRules.gitIdentity("alice", linked, spec),
        )
        val noId = RunnerGitHubToken.Linked("t", null, "octo")
        assertEquals("octo@users.noreply.github.com", CloudEnvironmentRules.gitIdentity("alice", noId, spec).email)
        assertEquals(
            GitIdentity("Alice.Dupont", "alice.dupont@users.noreply.ostrom.invalid"),
            CloudEnvironmentRules.gitIdentity("Alice.Dupont", RunnerGitHubToken.NotLinked, spec),
        )
        assertEquals(
            "j-r-me-x@acme.dev",
            CloudEnvironmentRules.gitIdentity(
                "-Jérôme x",
                RunnerGitHubToken.Unavailable,
                spec.copy(gitEmailDomain = "acme.dev"),
            ).email.removePrefix("-"),
        )
        assertEquals(
            "member@users.noreply.ostrom.invalid",
            CloudEnvironmentRules.gitIdentity("...", RunnerGitHubToken.NotLinked, spec).email,
        )
    }

    @Test
    fun aReportedFailureMustNameAStepOfTheRecipe() {
        val spec = CloudEnvironmentSpec(
            repositories = listOf(repo),
            setup = CloudSetup(create = listOf("a"), start = listOf("b", "c")),
        )
        val valid = listOf(
            PreparationFailure(PreparationStep.CLONE, 0, 128),
            PreparationFailure(PreparationStep.CHECKOUT, 0),
            PreparationFailure(PreparationStep.FETCH, null, 0),
            PreparationFailure(PreparationStep.SETUP_CREATE, 0, 255),
            PreparationFailure(PreparationStep.SETUP_START, 1, 1),
            PreparationFailure(PreparationStep.GIT_IDENTITY),
        )
        valid.forEach { PreparationFailures.validate(it, spec) }
        val invalid = listOf(
            PreparationFailure(PreparationStep.CLONE, 1),
            PreparationFailure(PreparationStep.SETUP_CREATE, 1),
            PreparationFailure(PreparationStep.SETUP_START, -1),
            PreparationFailure(PreparationStep.RUNNER, 0),
            PreparationFailure(PreparationStep.CLONE, 0, 256),
            PreparationFailure(PreparationStep.CLONE, 0, -1),
        )
        for (failure in invalid) {
            assertFailsWith<InvalidCloudSessionRequestException>("$failure") {
                PreparationFailures.validate(failure, spec)
            }
        }
    }

    @Test
    fun onlyNetworkStepsMayBeRetried() {
        val retryable = PreparationStep.entries.filter(PreparationFailures::retryable)
        assertEquals(listOf(PreparationStep.CLONE, PreparationStep.FETCH, PreparationStep.RUNNER), retryable)
    }

    @Test
    fun theServerWritesTheMessageOfEveryStep() {
        val spec = CloudEnvironmentSpec(repositories = listOf(repo), workingDirectory = "api/src")
        fun message(step: PreparationStep, index: Int? = null, exit: Int? = null) =
            PreparationFailures.message(PreparationFailure(step, index, exit), spec, 3)
        val log = " Details are in /workspace/.ostrom/logs/prepare-e3.log."
        assertEquals(
            "Configuring the Git identity failed (exit code 1).",
            message(PreparationStep.GIT_IDENTITY, exit = 1),
        )
        assertEquals(
            "Cloning https://github.com/acme/api into api failed (exit code 128). Private repositories need the " +
                "owner's GitHub access.$log",
            message(PreparationStep.CLONE, 0, 128),
        )
        assertEquals(
            "Cloning a repository into the workspace failed. Private repositories need the owner's GitHub " +
                "access.$log",
            message(PreparationStep.CLONE),
        )
        assertEquals("Checking out main in api failed.$log", message(PreparationStep.CHECKOUT, 0))
        val branchOnly = spec.copy(repositories = listOf(repo.copy(ref = null)))
        assertEquals(
            "Checking out task-1 in api failed.$log",
            PreparationFailures.message(PreparationFailure(PreparationStep.CHECKOUT, 0), branchOnly, 3),
        )
        val neither = spec.copy(repositories = listOf(repo.copy(ref = null, branch = null)))
        assertEquals(
            "Checking out the requested ref in api failed.$log",
            PreparationFailures.message(PreparationFailure(PreparationStep.CHECKOUT, 0), neither, 3),
        )
        assertEquals("Checking out the requested ref in a repository failed.$log", message(PreparationStep.CHECKOUT))
        assertEquals(
            "Fetching api failed. Private repositories need the owner's GitHub access.$log",
            message(PreparationStep.FETCH, 0),
        )
        assertEquals(
            "Fetching a repository failed. Private repositories need the owner's GitHub access.$log",
            message(PreparationStep.FETCH),
        )
        assertEquals(
            "The working directory api/src does not exist after cloning.",
            message(PreparationStep.WORKING_DIRECTORY),
        )
        assertEquals(
            "The working directory . does not exist after cloning.",
            PreparationFailures.message(
                PreparationFailure(PreparationStep.WORKING_DIRECTORY),
                CloudEnvironmentSpec(),
                3,
            ),
        )
        assertEquals(
            "Setup command 2 (create) failed (exit code 2).$log",
            message(PreparationStep.SETUP_CREATE, 1, 2),
        )
        assertEquals("Setup command 1 (start) failed.$log", message(PreparationStep.SETUP_START))
        assertEquals("Setup command 1 (create) failed.$log", message(PreparationStep.SETUP_CREATE))
        assertEquals("The runner could not prepare the environment.", message(PreparationStep.RUNNER))
    }

    @Test
    fun wireNamesAreReadBackAndUnknownOnesAreNot() {
        PreparationPhase.entries.forEach { assertEquals(it, PreparationPhase.fromWire(it.wire)) }
        PreparationStep.entries.forEach { assertEquals(it, PreparationStep.fromWire(it.wire)) }
        assertNull(PreparationPhase.fromWire("securing_workspace"))
        assertNull(PreparationStep.fromWire("workspace"))
    }

    @Test
    fun aPreparedEnvironmentNamesNoValueInALogLine() {
        val prepared = PreparedEnvironment(
            CloudEnvironmentSpec(repositories = listOf(repo)),
            GitIdentity("octo", "o@x"),
            RunnerVariableValues(mapOf("TOKEN" to "s3cret"), emptyList()),
        )
        assertFalse("s3cret" in prepared.toString())
        assertEquals(
            "PreparedEnvironment(repositories=1, git=octo, variables=RunnerVariableValues([TOKEN], unavailable=[]))",
            prepared.toString(),
        )
    }
}
