// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.usecases

import dev.ostrom.core.api.models.Notification
import dev.ostrom.core.api.models.NotificationStatus
import dev.ostrom.core.api.models.PackageMode
import dev.ostrom.core.receipts.Digests
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.receipts.domain.usecases.isoMillis
import dev.ostrom.server.sessions.domain.models.SessionAccess
import dev.ostrom.server.workflowruns.domain.models.NotInPackageException
import dev.ostrom.server.workflowruns.domain.models.RunPackageRevokedException
import dev.ostrom.server.workflowruns.domain.repositories.RunQuery
import dev.ostrom.server.workflowruns.domain.repositories.WorkflowRunsRepository
import dev.ostrom.server.workflowruns.domain.services.Delivery
import dev.ostrom.server.workflowruns.domain.services.NotificationChannels
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
        val pack = access.pack(actor, request.digest)
        if (pack.envelope.revoked) throw RunPackageRevokedException()
        val notify = pack.notifications.firstOrNull { it.id == request.notifyId && it.nodeId == request.nodeId }
            ?: throw NotInPackageException("Notification ${request.notifyId.take(RunTexts.ID)}")
        val dev = session?.dev ?: SessionAccess.person(actor)
        val repo = session?.repo ?: pack.envelope.repo
        val command = request.command.orEmpty().replace(BRANCH_NOTE, "").take(RunTexts.COMMAND)
        val text = notify.message
            .replace("{dev}", dev)
            .replace("{repo}", repo.orEmpty())
            .replace("{command}", command)
            .replace("{session}", (session?.id ?: "-").take(SHORT_SESSION))
        // The key of the action is the command's digest: the text kept may be masked, a failed send may go again.
        val actionKey = Digests.sha256Hex(request.command.orEmpty().take(RunTexts.COMMAND).encodeToByteArray())
        // A hook passing twice on the same action gets the first notification back, and nothing is sent again.
        session?.let { repository.firstNotification(actor.orgId, it.id, notify.id, actionKey) }?.let { return it }
        val delivery = if (notify.mode == PackageMode.SHADOW) {
            Delivery(NotificationStatus.LOGGED_ONLY)
        } else {
            channels.deliver(actor.orgId, notify.channel, text)
        }
        val notification = Notification(
            id = "ntf_" + Uuid.random().toHexString(),
            at = clock.now().isoMillis(),
            sessionId = session?.id,
            digest = pack.digest,
            version = pack.version,
            nodeId = notify.nodeId,
            notifyId = notify.id,
            channel = notify.channel,
            mode = notify.mode,
            status = delivery.status,
            text = text,
            dev = dev,
            repo = repo,
            error = delivery.error,
        )
        return repository.addNotification(actor.orgId, notification, actionKey)
    }

    private companion object {
        /** The note the launcher appends to a command, never part of the message. */
        val BRANCH_NOTE = Regex("\\s+#\\s*current branch:.*$")

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
     * @throws dev.ostrom.server.workflowruns.domain.models.RunSessionNotFoundException When the session is not one the
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
        return repository.notifications(actor.orgId, query)
    }
}
