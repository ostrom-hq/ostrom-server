// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.domain

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.usecases.AskGate
import dev.ostrom.server.gates.domain.usecases.AskGateUseCase
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** What a verified webhook did. */
enum class WebhookOutcome {
    /** A pull request's gate was asked and its check published. */
    CHECKED,

    /** An installation was forgotten. */
    UNINSTALLED,

    /** Who installed the App was recorded, for the install callback to check (AUDIT-SECU-2 M1). */
    RECORDED,

    /** A delivery already handled: a replay, ignored (AUDIT-SECU-2 B3). */
    REPLAYED,

    /** Nothing to do: another event or action, or an installation no organization owns. */
    IGNORED,
}

/** Handles a webhook whose signature is already verified. */
interface HandleWebhookUseCase {
    /**
     * @param event The `X-GitHub-Event` header.
     * @param delivery The `X-GitHub-Delivery` header, GitHub's id of this delivery (kept by a redelivery).
     * @param body The body.
     * @return What was done.
     * @throws OstromException 400 without a delivery id.
     */
    suspend operator fun invoke(event: String?, delivery: String?, body: String): WebhookOutcome
}

/**
 * [HandleWebhookUseCase]: a pull request opened, updated or reopened asks the gate of its merge (as the CI, for the
 * organization that installed the App, the author being who asks) and publishes `ostrom-gate` on its head; the same
 * request as the agent's `gh pr merge` when the head, base and gate match (gates module, APPROBATION). An
 * installation created on GitHub records who installed it, which the install callback checks (AUDIT-SECU-2 M1); one
 * deleted is forgotten, linked or not. Each delivery is handled once (AUDIT-SECU-2 B3): a replay of a captured
 * delivery is ignored, and a delivery whose handling failed is released, so that GitHub's redelivery is handled.
 *
 * @param repository Installations.
 * @param origins Who installed each installation, and the deliveries handled.
 * @param ask Asks gates.
 * @param checks Publishes the check.
 * @param logger Logs a refused gate.
 * @param clock The clock.
 */
class HandleWebhookUseCaseImpl(
    private val repository: GitHubRepository,
    private val origins: GitHubOriginsRepository,
    private val ask: AskGateUseCase,
    private val checks: GitHubGateChecks,
    private val logger: Logger,
    private val clock: Clock,
) : HandleWebhookUseCase {
    override suspend fun invoke(event: String?, delivery: String?, body: String): WebhookOutcome {
        val id = delivery?.takeIf { DELIVERY.matches(it) }
            ?: throw OstromException(ErrorKind.INVALID_REQUEST, "This webhook has no X-GitHub-Delivery id.")
        if (!origins.claimDelivery(id, clock.now())) return WebhookOutcome.REPLAYED
        var handled = false
        try {
            val outcome = handle(event, body)
            handled = true
            return outcome
        } finally {
            // A failure (GitHub down while the check is published) leaves the delivery to GitHub's redelivery.
            if (!handled) withContext(NonCancellable) { origins.releaseDelivery(id) }
        }
    }

    private suspend fun handle(event: String?, body: String): WebhookOutcome {
        val payload = parse(event, body)
        val installationId = payload?.installation?.id
        return when {
            payload == null || installationId == null -> WebhookOutcome.IGNORED
            event == INSTALLATION && payload.action == "created" -> created(installationId, payload)
            event == INSTALLATION && payload.action == "deleted" -> {
                repository.forgetInstallation(installationId)
                WebhookOutcome.UNINSTALLED
            }
            event == "pull_request" && payload.action in GATED_ACTIONS ->
                repository.installationById(installationId)?.let { pullRequest(it, payload) } ?: WebhookOutcome.IGNORED
            else -> WebhookOutcome.IGNORED
        }
    }

    private suspend fun created(installationId: Long, payload: Payload): WebhookOutcome {
        val sender = payload.sender?.login
        val account = payload.installation?.account
        if (sender == null || account == null) return WebhookOutcome.IGNORED
        val origin = GitHubInstallationOrigin(installationId, sender, account.login, account.type)
        origins.saveOrigin(origin, clock.now())
        return WebhookOutcome.RECORDED
    }

    private fun parse(event: String?, body: String): Payload? = try {
        json.decodeFromString(Payload.serializer(), body)
    } catch (error: SerializationException) {
        logger.warn("github webhook not understood", mapOf("event" to event), error)
        null
    }

    private suspend fun pullRequest(installation: GitHubInstallation, payload: Payload): WebhookOutcome {
        val pull = payload.pullRequest?.takeIf { it.state == "open" }
        val repo = payload.repository?.fullName
        val view = if (pull == null || repo == null) null else askFor(installation, repo, pull)
        if (view == null) return WebhookOutcome.IGNORED
        checks.publish(view.gate, view.requests)
        return WebhookOutcome.CHECKED
    }

    private suspend fun askFor(installation: GitHubInstallation, repo: String, pull: Pull): GateView? {
        val actor = GateActor("github", installation.orgId, ActorKind.SERVICE, personId = pull.user.login)
        val asked = AskGate(
            sessionId = null,
            runId = null,
            repo = repo,
            realRepo = null,
            actionClass = if (pull.base.ref in MAIN_BRANCHES) "push-main" else "merge",
            tool = "GitHub",
            command = "gh pr merge ${pull.number}",
            harnessVersion = null,
            push = null,
            pullRequest = pull.number,
            diff = null,
            via = AskedVia.CI,
        )
        return try {
            ask(actor, asked)
        } catch (error: OstromException) {
            // GitHub did not describe the pull request (closed meanwhile, or not answering): no check, no yes.
            logger.warn("pull request gate refused", mapOf("repo" to repo, "pr" to pull.number), error)
            null
        }
    }

    @Serializable
    private data class Payload(
        val action: String? = null,
        val installation: Installation? = null,
        val sender: Login? = null,
        val repository: Repository? = null,
        @SerialName("pull_request") val pullRequest: Pull? = null,
    )

    @Serializable
    private data class Installation(val id: Long, val account: Account? = null)

    @Serializable
    private data class Account(val login: String, val type: String)

    @Serializable
    private data class Repository(@SerialName("full_name") val fullName: String)

    @Serializable
    private data class Pull(val number: Int, val state: String, val base: Ref, val user: Login)

    @Serializable
    private data class Ref(val ref: String)

    @Serializable
    private data class Login(val login: String)
}

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val json = Json { ignoreUnknownKeys = true }
private val GATED_ACTIONS = setOf("opened", "reopened", "synchronize", "ready_for_review", "edited")
private val MAIN_BRANCHES = setOf("main", "master")
private const val INSTALLATION = "installation"

// GitHub's delivery ids are GUIDs; bounded so that a forged header never grows the table's key.
private val DELIVERY = Regex("^[0-9A-Fa-f-]{1,64}$")
