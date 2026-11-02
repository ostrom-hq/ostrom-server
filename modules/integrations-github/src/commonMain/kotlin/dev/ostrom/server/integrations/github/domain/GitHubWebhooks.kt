// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.domain

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.usecases.AskGate
import dev.ostrom.server.gates.domain.usecases.AskGateUseCase
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.logging.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** What a verified webhook did. */
enum class WebhookOutcome {
    /** A pull request's gate was asked and its check published. */
    CHECKED,

    /** An installation was forgotten. */
    UNINSTALLED,

    /** Nothing to do: another event or action, or an installation no organization owns. */
    IGNORED,
}

/** Handles a webhook whose signature is already verified. */
interface HandleWebhookUseCase {
    /**
     * @param event The `X-GitHub-Event` header.
     * @param body The body.
     * @return What was done.
     */
    suspend operator fun invoke(event: String?, body: String): WebhookOutcome
}

/**
 * [HandleWebhookUseCase]: a pull request opened, updated or reopened asks the gate of its merge (as the CI, for the
 * organization that installed the App, the author being who asks) and publishes `ostrom-gate` on its head; the same
 * request as the agent's `gh pr merge` when the head, base and gate match (gates module, APPROBATION). An
 * installation deleted on GitHub is forgotten.
 *
 * @param repository Installations.
 * @param ask Asks gates.
 * @param checks Publishes the check.
 * @param logger Logs a refused gate.
 */
class HandleWebhookUseCaseImpl(
    private val repository: GitHubRepository,
    private val ask: AskGateUseCase,
    private val checks: GitHubGateChecks,
    private val logger: Logger,
) : HandleWebhookUseCase {
    override suspend fun invoke(event: String?, body: String): WebhookOutcome {
        val payload = parse(event, body)
        val installation = payload?.installation?.id?.let { repository.installationById(it) }
        return when {
            payload == null || installation == null -> WebhookOutcome.IGNORED
            event == "installation" && payload.action == "deleted" -> {
                repository.removeInstallation(installation)
                WebhookOutcome.UNINSTALLED
            }
            event == "pull_request" && payload.action in GATED_ACTIONS -> pullRequest(installation, payload)
            else -> WebhookOutcome.IGNORED
        }
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
        val installation: Id? = null,
        val repository: Repository? = null,
        @SerialName("pull_request") val pullRequest: Pull? = null,
    )

    @Serializable
    private data class Id(val id: Long)

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
