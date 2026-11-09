// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.domain.usecases

import run.ostrom.core.api.models.Notification
import run.ostrom.core.api.models.NotificationStatus
import run.ostrom.core.api.models.PackageMode
import run.ostrom.core.receipts.Digests
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.errors.Pages
import run.ostrom.server.receipts.domain.usecases.isoMillis
import run.ostrom.server.sessions.domain.models.SessionAccess
import run.ostrom.server.workflowruns.domain.models.NotInPackageException
import run.ostrom.server.workflowruns.domain.models.RunPackageRevokedException
import run.ostrom.server.workflowruns.domain.repositories.RunQuery
import run.ostrom.server.workflowruns.domain.repositories.WorkflowRunsRepository
import run.ostrom.server.workflowruns.domain.services.NotificationChannels
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * What the hook asks Ostrom to send (contract `sendNotification`).
 *
 * @property sessionId The session, or null.
 * @property digest The package.
 * @property nodeId The notify node.
 * @property notifyId The notification of the package.
 * @property command The command that triggered it.
 */
data class NotificationRequest(
    val sessionId: String?,
    val digest: String,
    val nodeId: String,
    val notifyId: String,
    val command: String?,
)

/**
 * Sends the message of a compiled notify node (contract `sendNotification`, prototype `POST /api/compiler/notify`):
 * the text is the package's, never the hook's, and the channel's secret never reaches the machine.
 */
interface SendNotificationUseCase {
    /**
     * Sends it, once per action of a session: a hook passing twice gets the first notification back.
     *
     * @param actor The caller: the agent token of the session's launch, or the session's person.
     * @param request What the hook asks.
     *
     * @return The notification: sent, logged only (a shadow node), or not sent with why.
     *
     * @throws RunPackageRevokedException When the package was revoked.
     * @throws NotInPackageException When the package has no such notification for that node.
     */
    suspend operator fun invoke(actor: GateActor, request: NotificationRequest): Notification
}

/**
 * [SendNotificationUseCase] on the repository and the channels.
 *
 * @param access Who may write for a session.
 * @param repository The notifications.
 * @param channels Delivers them.
 * @param clock The clock.
 */
class SendNotificationUseCaseImpl(
    private val access: RunAccess,
    private val repository: WorkflowRunsRepository,
    private val channels: NotificationChannels,
    private val clock: Clock,
) : SendNotificationUseCase {
    override suspend fun invoke(actor: GateActor, request: NotificationRequest): Notification {
        RunTexts.bounded("sessionId", request.sessionId)
        val session = access.writing(actor, request.sessionId)
        val pack = access.pack(actor, request.digest, session)
        if (pack.envelope.revoked) throw RunPackageRevokedException()
        val notify = pack.notifications.firstOrNull { it.id == request.notifyId && it.nodeId == request.nodeId }
            ?: throw NotInPackageException("Notification ${request.notifyId.take(RunTexts.ID)}")
        val dev = if (session == null) SessionAccess.person(actor) else session.dev
        val repo = session?.repo ?: pack.envelope.repo
        // The command reaches a channel and every member's list: its secrets are masked first.
        val command = RunTexts.masked(request.command.orEmpty().replace(BRANCH_NOTE, "")).take(RunTexts.COMMAND)
        val values = mapOf(
            "dev" to dev,
            "repo" to repo.orEmpty(),
            "command" to command,
            "session" to if (session == null) "-" else session.id.take(SHORT_SESSION),
        )
        // One pass: a `{session}` inside the command is text, never a placeholder.
        val text = PLACEHOLDER.replace(notify.message) { values.getValue(it.groupValues[1]) }
        // The key of the action is the command's digest: one notification per action of a session.
        val actionKey = Digests.sha256Hex(request.command.orEmpty().take(RunTexts.COMMAND).encodeToByteArray())
        val shadow = notify.mode == PackageMode.SHADOW
        val claim = Notification(
            id = "ntf_" + Uuid.random().toHexString(),
            at = clock.now().isoMillis(),
            sessionId = session?.id,
            digest = pack.digest,
            version = pack.version,
            nodeId = notify.nodeId,
            notifyId = notify.id,
            channel = notify.channel,
            mode = notify.mode,
            status = if (shadow) NotificationStatus.LOGGED_ONLY else NotificationStatus.QUEUED,
            text = text,
            dev = dev,
            repo = repo,
        )
        // The action is claimed before anything is sent: two passes of a hook at once send it once, and the second
        // gets the first one back. A crash before the update leaves it queued, never sent twice.
        val stored = repository.addNotification(actor.orgId, claim, actionKey)
        if (stored.id != claim.id || shadow) return stored
        val delivery = channels.deliver(actor.orgId, notify.channel, text)
        val delivered = claim.copy(status = delivery.status, error = delivery.error)
        repository.updateNotification(actor.orgId, delivered)
        return delivered
    }

    private companion object {
        /** The session in a message: its first characters. */
        const val SHORT_SESSION = 8
    }
}

/** Lists the notifications (contract `listNotifications`): the console's, and an agent's for its own session. */
interface ListNotificationsUseCase {
    /**
     * Lists the notifications, newest first.
     *
     * @param actor The caller.
     * @param query Which; an agent token names its own session.
     *
     * @return The notifications.
     *
     * @throws run.ostrom.server.workflowruns.domain.models.RunSessionNotFoundException When the session is not one the
     *   caller may read.
     */
    suspend operator fun invoke(actor: GateActor, query: RunQuery): List<Notification>
}

/**
 * [ListNotificationsUseCase] on the repository.
 *
 * @param access Who may read a session.
 * @param repository The notifications.
 */
class ListNotificationsUseCaseImpl(
    private val access: RunAccess,
    private val repository: WorkflowRunsRepository,
) : ListNotificationsUseCase {
    override suspend fun invoke(actor: GateActor, query: RunQuery): List<Notification> {
        val sessionId = query.sessionId
        if (sessionId != null) access.reading(actor, sessionId)
        if (sessionId == null && actor.kind == ActorKind.AGENT_SESSION) return emptyList()
        // A `before` that names no notification of the organization (forged, or another's) is a 400, not an empty page.
        return Pages.checked("before", query.before, repository.notifications(actor.orgId, query)) {
            repository.hasNotification(actor.orgId, it)
        }
    }
}

/** The note the launcher appends to a command, never part of the message. */
private val BRANCH_NOTE = Regex("\\s+#\\s*current branch:.*$")

/** The placeholders of a package's message. */
private val PLACEHOLDER = Regex("\\{(dev|repo|command|session)\\}")
