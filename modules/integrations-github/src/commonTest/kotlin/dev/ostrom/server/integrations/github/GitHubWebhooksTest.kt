// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.usecases.AskGate
import dev.ostrom.server.gates.domain.usecases.AskGateUseCase
import dev.ostrom.server.integrations.github.domain.GitHubAppNotConfiguredException
import dev.ostrom.server.integrations.github.domain.GitHubGateChecks
import dev.ostrom.server.integrations.github.domain.GitHubInstallation
import dev.ostrom.server.integrations.github.domain.GitHubInstalls
import dev.ostrom.server.integrations.github.domain.InstallationNotYoursException
import dev.ostrom.server.integrations.github.domain.HandleWebhookUseCaseImpl
import dev.ostrom.server.integrations.github.domain.WebhookOutcome
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.logging.LogLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class GitHubWebhooksTest {
    private val repository = MemoryGitHubRepository()
    private val api = FakeGitHubApi()
    private val clock = SettableClock()
    private val logger = RecordingLogger()
    private val asked = mutableListOf<Pair<GateActor, AskGate>>()
    private val ask = object : AskGateUseCase {
        override suspend fun invoke(actor: GateActor, ask: AskGate): GateView {
            asked += actor to ask
            if (ask.pullRequest == 9) throw OstromException(ErrorKind.CONFLICT, "GitHub must describe it.")
            val base = if (ask.actionClass == "merge") "develop" else "main"
            return GateView(mergeGate(base = base), emptyList(), emptyList())
        }
    }
    private val webhooks = HandleWebhookUseCaseImpl(
        repository,
        ask,
        GitHubGateChecks(repository, api, "https://console.example", clock),
        logger,
    )

    private fun pull(action: String, number: Int = 6, base: String = "main", state: String = "open") =
        """{"action":"$action","installation":{"id":42},"repository":{"full_name":"acme/api"},""" +
            """"pull_request":{"number":$number,"state":"$state","base":{"ref":"$base"},"user":{"login":"dev"},""" +
            """"head":{"sha":"$SHA"}}}"""

    @Test
    fun anOpenedPullRequestAsksTheGateOfItsMergeAndPublishesTheCheck() = runTest {
        repository.saveInstallation(GitHubInstallation(ORG, 42, "acme"), NOW)
        assertEquals(WebhookOutcome.CHECKED, webhooks("pull_request", pull("opened")))
        val (actor, request) = asked.single()
        assertEquals(GateActor("github", ORG, ActorKind.SERVICE, personId = "dev"), actor)
        assertEquals("push-main", request.actionClass)
        assertEquals(6, request.pullRequest)
        assertEquals(AskedVia.CI, request.via)
        assertEquals("acme/api", request.repo)
        assertEquals(1, api.published.size)

        assertEquals(WebhookOutcome.CHECKED, webhooks("pull_request", pull("synchronize", base = "develop")))
        assertEquals("merge", asked.last().second.actionClass)
    }

    @Test
    fun otherEventsAndUnknownInstallationsAreIgnored() = runTest {
        assertEquals(WebhookOutcome.IGNORED, webhooks("pull_request", pull("opened")), "No organization installed it")
        repository.saveInstallation(GitHubInstallation(ORG, 42, "acme"), NOW)
        assertEquals(WebhookOutcome.IGNORED, webhooks("pull_request", pull("closed")))
        assertEquals(WebhookOutcome.IGNORED, webhooks("pull_request", pull("opened", state = "closed")))
        assertEquals(WebhookOutcome.IGNORED, webhooks("push", pull("opened")))
        assertEquals(WebhookOutcome.IGNORED, webhooks(null, """{"zen":"Keep it logically awesome."}"""))
        val noPull = """{"action":"opened","installation":{"id":42}}"""
        assertEquals(WebhookOutcome.IGNORED, webhooks("pull_request", noPull))
        val noRepo = """{"action":"opened","installation":{"id":42},"pull_request":{"number":6,"state":"open",""" +
            """"base":{"ref":"main"},"user":{"login":"dev"}}}"""
        assertEquals(WebhookOutcome.IGNORED, webhooks("pull_request", noRepo))
        assertEquals(WebhookOutcome.IGNORED, webhooks("pull_request", "not json"))
        assertEquals(WebhookOutcome.IGNORED, webhooks("installation", pull("created")))
        assertTrue(asked.isEmpty())
        assertEquals(listOf(LogLevel.WARN to "github webhook not understood"), logger.lines)
    }

    @Test
    fun aRefusedGateLeavesNoCheck() = runTest {
        repository.saveInstallation(GitHubInstallation(ORG, 42, "acme"), NOW)
        assertEquals(WebhookOutcome.IGNORED, webhooks("pull_request", pull("reopened", number = 9)))
        assertTrue(api.published.isEmpty())
        assertEquals(LogLevel.WARN to "pull request gate refused", logger.lines.single())
    }

    @Test
    fun anUninstalledAppIsForgotten() = runTest {
        repository.saveInstallation(GitHubInstallation(ORG, 42, "acme"), NOW)
        val deleted = """{"action":"deleted","installation":{"id":42}}"""
        assertEquals(WebhookOutcome.UNINSTALLED, webhooks("installation", deleted))
        assertTrue(repository.installations.isEmpty())
    }

    @Test
    fun anInstallLinksTheInstallationToTheOrganizationThatStartedItOnce() = runTest {
        val installs = GitHubInstalls(repository, api, { "ostrom-dev" }, "https://github.com", clock)
        val link = installs.start(ORG, "owner", SESSION)
        assertTrue(link.startsWith("https://github.com/apps/ostrom-dev/installations/new?state="))
        val state = link.substringAfter("state=")
        assertEquals(64, state.length)
        api.accounts[42] = "acme"

        assertEquals(GitHubInstallation(ORG, 42, "acme"), installs.finish("42", "code-acme", state, SESSION))
        assertEquals(GitHubInstallation(ORG, 42, "acme"), repository.installation(ORG))
        val reused = assertFailsWith<OstromException> { installs.finish("42", "code-acme", state, SESSION) }
        assertEquals(ErrorKind.FORBIDDEN, reused.kind)

        val expired = installs.start(ORG, "owner", SESSION).substringAfter("state=")
        clock.now += 11.minutes
        val late = assertFailsWith<OstromException> { installs.finish("42", "code-acme", expired, SESSION) }
        assertEquals(ErrorKind.FORBIDDEN, late.kind)

        val long = "s".repeat(129)
        val malformed = listOf(
            Triple(null, "code-acme", state), Triple("x", "code-acme", state), Triple("42", "code-acme", null),
            Triple("42", "code-acme", ""), Triple("42", "code-acme", long), Triple("42", null, state),
            Triple("42", "", state), Triple("42", long, state),
        )
        for ((id, code, value) in malformed) {
            val refused = assertFailsWith<OstromException> { installs.finish(id, code, value, SESSION) }
            assertEquals(ErrorKind.INVALID_REQUEST, refused.kind, "$id $code $value")
        }
        // Installation 7 is reachable but not one of this App's installations GitHub knows.
        api.reachable["gho_acme"] = setOf(42L, 7L)
        val unknown = installs.start(ORG, "owner", SESSION).substringAfter("state=")
        val notTheApp = assertFailsWith<OstromException> { installs.finish("7", "code-acme", unknown, SESSION) }
        assertEquals(ErrorKind.INVALID_REQUEST, notTheApp.kind)

        val other = installs.start(OTHER_ORG, "owner", SESSION).substringAfter("state=")
        assertEquals(
            ErrorKind.CONFLICT,
            assertFailsWith<OstromException> { installs.finish("42", "code-acme", other, SESSION) }.kind,
        )
        val again = installs.start(ORG, "owner", SESSION).substringAfter("state=")
        val reinstalled = installs.finish("42", "code-acme", again, SESSION)
        assertEquals(GitHubInstallation(ORG, 42, "acme"), reinstalled, "Installed again")

        val unconfigured = GitHubInstalls(repository, api, { null }, "https://github.com", clock)
        assertFailsWith<GitHubAppNotConfiguredException> { unconfigured.start(ORG, "owner", SESSION) }
    }

    @Test
    fun anInstallationIsLinkedOnlyIfThePersonWhoCameBackReachesIt() = runTest {
        val installs = GitHubInstalls(repository, api, { "ostrom-dev" }, "https://github.com", clock)
        api.accounts[42] = "acme"
        api.accounts[43] = "globex"
        // The owner of org B, who reaches only 43, comes back with the id of acme's installation.
        val stolen = installs.start(OTHER_ORG, "owner-b", SESSION).substringAfter("state=")
        val refused = assertFailsWith<InstallationNotYoursException> {
            installs.finish("42", "code-globex", stolen, SESSION)
        }
        assertEquals(ErrorKind.FORBIDDEN, refused.kind)
        assertEquals("installation_not_yours", refused.type)
        assertTrue(repository.installations.isEmpty(), "Nothing linked")
        assertEquals(
            ErrorKind.FORBIDDEN,
            assertFailsWith<OstromException> { installs.finish("43", "code-globex", stolen, SESSION) }.kind,
            "The state served once, even refused",
        )
        // A code GitHub does not accept proves nobody.
        val forged = installs.start(OTHER_ORG, "owner-b", SESSION).substringAfter("state=")
        assertFailsWith<InstallationNotYoursException> { installs.finish("43", "code-forged", forged, SESSION) }

        val own = installs.start(OTHER_ORG, "owner-b", SESSION).substringAfter("state=")
        assertEquals(GitHubInstallation(OTHER_ORG, 43, "globex"), installs.finish("43", "code-globex", own, SESSION))
    }

    @Test
    fun onlyTheSessionThatStartedAnInstallFinishesIt() = runTest {
        val installs = GitHubInstalls(repository, api, { "ostrom-dev" }, "https://github.com", clock)
        api.accounts[42] = "acme"
        val state = installs.start(ORG, "owner", SESSION).substringAfter("state=")
        // The state brought back by another session (a link sent to someone else) matches nothing.
        val elsewhere = assertFailsWith<OstromException> { installs.finish("42", "code-acme", state, "another") }
        assertEquals(ErrorKind.FORBIDDEN, elsewhere.kind)
        val signedOut = assertFailsWith<OstromException> { installs.finish("42", "code-acme", state, null) }
        assertEquals(ErrorKind.UNAUTHORIZED, signedOut.kind)
        assertTrue(repository.installations.isEmpty())
        // Its own session still finishes it, once.
        assertEquals(GitHubInstallation(ORG, 42, "acme"), installs.finish("42", "code-acme", state, SESSION))
        assertFailsWith<OstromException> { installs.finish("42", "code-acme", state, SESSION) }
    }
}

private const val SESSION = "session-of-the-owner"
