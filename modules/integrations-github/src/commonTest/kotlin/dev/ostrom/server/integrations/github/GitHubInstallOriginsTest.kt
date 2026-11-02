// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github

import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.usecases.AskGate
import dev.ostrom.server.gates.domain.usecases.AskGateUseCase
import dev.ostrom.server.integrations.github.domain.GitHubGateChecks
import dev.ostrom.server.integrations.github.domain.GitHubInstallation
import dev.ostrom.server.integrations.github.domain.GitHubInstallationOrigin
import dev.ostrom.server.integrations.github.domain.GitHubInstalls
import dev.ostrom.server.integrations.github.domain.HandleWebhookUseCaseImpl
import dev.ostrom.server.integrations.github.domain.InstallationNotYoursException
import dev.ostrom.server.integrations.github.domain.InstallationPendingException
import dev.ostrom.server.integrations.github.domain.WebhookOutcome
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/** AUDIT-SECU-2: M1 (who installed the App is who links it) and B3 (a delivery is handled once). */
class GitHubInstallOriginsTest {
    private val repository = MemoryGitHubRepository()
    private val api = FakeGitHubApi().apply {
        accounts[42] = "acme"
        accounts[44] = "carol"
        accounts[45] = "dave"
    }
    private val clock = SettableClock()
    private val logger = RecordingLogger()
    private val asked = mutableListOf<AskGate>()
    private val ask = object : AskGateUseCase {
        override suspend fun invoke(actor: GateActor, ask: AskGate): GateView {
            asked += ask
            check(ask.pullRequest != 13) { "The database is down." }
            return GateView(mergeGate(), emptyList(), emptyList())
        }
    }
    private val webhooks = HandleWebhookUseCaseImpl(
        repository,
        ask,
        GitHubGateChecks(repository, api, "https://console.example", clock),
        logger,
        clock,
    )
    private val installs = GitHubInstalls(repository, api, { "ostrom-dev" }, "https://github.com", clock)

    private suspend fun state(org: Uuid = ORG) = installs.start(org, "owner", SESSION).substringAfter("state=")

    private fun created(id: Long, sender: String?, account: String?, type: String = "Organization"): String {
        val by = sender?.let { ""","sender":{"login":"$it"}""" }.orEmpty()
        val on = account?.let { ""","account":{"login":"$it","type":"$type"}""" }.orEmpty()
        return """{"action":"created","installation":{"id":$id$on}$by}"""
    }

    @Test
    fun theWebhookOfAnInstallRecordsWhoInstalledIt() = runTest {
        assertEquals(WebhookOutcome.RECORDED, webhooks("installation", "a-1", created(42, "acme-admin", "acme")))
        assertEquals(ACME_ORIGIN, repository.origin(42))
        assertEquals(WebhookOutcome.IGNORED, webhooks("installation", "a-2", created(46, null, "acme")), "No sender")
        assertEquals(WebhookOutcome.IGNORED, webhooks("installation", "a-3", created(46, "x", null)), "No account")
        assertNull(repository.origin(46))
        // An installation deleted before any organization linked it is forgotten too.
        val deleted = """{"action":"deleted","installation":{"id":42}}"""
        assertEquals(WebhookOutcome.UNINSTALLED, webhooks("installation", "a-4", deleted))
        assertNull(repository.origin(42))
    }

    @Test
    fun aMemberWithReadAccessWhoReplaysTheCallbackIsRefused() = runTest {
        repository.saveOrigin(ACME_ORIGIN, NOW)
        // globex-admin can read acme on GitHub: GitHub lists acme's installation among theirs.
        api.reachable["gho_globex"] = setOf(42L, 43L)
        val replayed = assertFailsWith<InstallationNotYoursException> {
            installs.finish("42", "code-globex", state(OTHER_ORG), SESSION)
        }
        assertEquals("installation_not_yours", replayed.type)
        assertTrue(repository.installations.isEmpty(), "Nothing linked to the other organization")
        // The admin who installed it links it to their organization: no 409 left behind.
        assertEquals(GitHubInstallation(ORG, 42, "acme"), installs.finish("42", "code-acme", state(), SESSION))
    }

    @Test
    fun theAdminWhoInstalledItIsAcceptedWhateverTheCaseOfTheirLogin() = runTest {
        repository.saveOrigin(ACME_ORIGIN.copy(sender = "Acme-Admin"), NOW)
        assertEquals(GitHubInstallation(ORG, 42, "acme"), installs.finish("42", "code-acme", state(), SESSION))
    }

    @Test
    fun onAPersonalAccountItsOwnerMustBeThePerson() = runTest {
        api.codes["code-carol"] = "gho_carol"
        api.reachable["gho_carol"] = setOf(44L, 45L)
        api.logins["gho_carol"] = "carol"
        repository.saveOrigin(GitHubInstallationOrigin(44, "carol", "carol", "User"), NOW)
        repository.saveOrigin(GitHubInstallationOrigin(45, "carol", "dave", "User"), NOW)
        assertFailsWith<InstallationNotYoursException> { installs.finish("45", "code-carol", state(), SESSION) }
        assertEquals(GitHubInstallation(ORG, 44, "carol"), installs.finish("44", "code-carol", state(), SESSION))
    }

    @Test
    fun beforeGitHubsWebhookTheInstallIsPendingNotAFailure() = runTest {
        val pending = assertFailsWith<InstallationPendingException> {
            installs.finish("42", "code-acme", state(), SESSION)
        }
        assertEquals(ErrorKind.UNAVAILABLE, pending.kind)
        assertEquals("installation_pending", pending.type)
        assertTrue(repository.installations.isEmpty())
        // The webhook a moment after the browser: the callback waits for it.
        launch {
            delay(1_500.milliseconds)
            repository.saveOrigin(ACME_ORIGIN, NOW)
        }
        assertEquals(GitHubInstallation(ORG, 42, "acme"), installs.finish("42", "code-acme", state(), SESSION))
    }

    @Test
    fun aDeliveryIsHandledOnceAndAFailedOneIsLeftToGitHubsRedelivery() = runTest {
        repository.saveInstallation(GitHubInstallation(ORG, 42, "acme"), NOW)
        val opened = pull(6)
        assertEquals(WebhookOutcome.CHECKED, webhooks("pull_request", "b-1", opened))
        assertEquals(WebhookOutcome.REPLAYED, webhooks("pull_request", "b-1", opened), "A captured delivery replayed")
        assertEquals(1, asked.size)

        val failing = pull(13)
        assertFailsWith<IllegalStateException> { webhooks("pull_request", "b-2", failing) }
        assertFalse("b-2" in repository.deliveries, "Released for the redelivery")
        assertFailsWith<IllegalStateException> { webhooks("pull_request", "b-2", failing) }
        assertEquals(3, asked.size, "GitHub's redelivery, same id, is handled")
    }

    @Test
    fun aWebhookWithoutADeliveryIdIsRefused() = runTest {
        for (delivery in listOf(null, "", "not a guid", "f".repeat(65))) {
            val refused = assertFailsWith<OstromException> { webhooks("pull_request", delivery, pull(6)) }
            assertEquals(ErrorKind.INVALID_REQUEST, refused.kind, "$delivery")
        }
        assertTrue(repository.deliveries.isEmpty())
    }

    private fun pull(number: Int) =
        """{"action":"opened","installation":{"id":42},"repository":{"full_name":"acme/api"},""" +
            """"pull_request":{"number":$number,"state":"open","base":{"ref":"main"},"user":{"login":"dev"},""" +
            """"head":{"sha":"$SHA"}}}"""
}

private const val SESSION = "session-of-the-owner"
