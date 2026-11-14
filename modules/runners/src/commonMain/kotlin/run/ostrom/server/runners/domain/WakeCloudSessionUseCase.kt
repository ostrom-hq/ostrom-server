// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.PrincipalKind
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What a wake did to a cloud session. */
enum class CloudSessionWake {
    /** It was paused (or pausing) and its desired state is now running: its next execution starts. */
    RESUMED,

    /** Nothing to do: it already runs, or is resuming. */
    NOT_PAUSED,

    /** No cloud session of the organization has this id (a session of a local launch, say). */
    NO_SESSION,

    /** It is over, or being deleted: it takes nothing. */
    ENDED,

    /** Its owner is no active member of the organization any more. */
    OWNER_GONE,

    /** Cloud sessions are closed to the organization. */
    CLOSED,

    /** The organization used the hours of its plan this period. */
    NO_HOURS,

    /** The organization runs as many sessions as its plan allows. */
    AT_CAP,
}

/** Wakes a cloud session for the answer to one of its waits (TeamCode's `CloudConversationService.wake`). */
interface WakeCloudSessionUseCase {
    /**
     * Resumes the session when it is paused and not ended, on its owner's behalf, with the rechecks of an owner's
     * resume (its owner still a member, the plan's hours and cap); a pausing one resumes once paused. Idempotent: a
     * session that runs is left as it is.
     *
     * @param orgId The organization.
     * @param sessionId The session the wait was asked in, as the wait names it.
     * @param waitId The wait answered, for the audit log.
     *
     * @return What it did; never a refusal thrown, the answer stays on the wait whatever happens here.
     */
    suspend operator fun invoke(orgId: Uuid, sessionId: String, waitId: Uuid): CloudSessionWake
}

/**
 * [WakeCloudSessionUseCase] on the repository and the plan. A resume it makes is written to the organization's audit
 * log as `cloud.session.woken`, by [WAKE_ACTOR], with the session's owner and the wait.
 *
 * @param repository The sessions.
 * @param plans The organizations' plans.
 * @param usage The hours used.
 * @param members Who is still a member; without it no owner is known and no session is woken.
 * @param audit The organization's audit log.
 * @param clock The time.
 */
class WakeCloudSessionUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val plans: CloudPlanProvider,
    private val usage: CloudUsageMeter,
    private val members: MemberDirectory?,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
) : WakeCloudSessionUseCase {
    override suspend fun invoke(orgId: Uuid, sessionId: String, waitId: Uuid): CloudSessionWake {
        val session = Uuid.parseOrNull(sessionId)?.let { repository.get(orgId, it) }
        return when {
            session == null -> CloudSessionWake.NO_SESSION
            session.state.isFinal || session.desiredState == DesiredState.TERMINATED -> CloudSessionWake.ENDED
            // An accepted transfer pauses the session; only the transfer resumes it, for the recipient.
            session.desiredState == DesiredState.RUNNING || session.transferId != null -> CloudSessionWake.NOT_PAUSED
            members?.isActiveMember(orgId, session.ownerId) != true -> CloudSessionWake.OWNER_GONE
            else -> refusal(session.orgId) ?: resume(session, waitId)
        }
    }

    /** Why the plan keeps the organization's sessions from resuming now, as an owner's resume refuses; null if none. */
    private suspend fun refusal(orgId: Uuid): CloudSessionWake? = try {
        requireHoursLeft(usage, plans.planFor(orgId), orgId, clock.now())
        null
    } catch (_: CloudSessionsClosedException) {
        CloudSessionWake.CLOSED
    } catch (_: CloudHoursExhaustedException) {
        CloudSessionWake.NO_HOURS
    }

    private suspend fun resume(session: CloudSession, waitId: Uuid): CloudSessionWake {
        val cap = plans.planFor(session.orgId).maxRunningSessions
        return when (repository.resumeWithinCap(session.orgId, session.id, cap, clock.now())) {
            is ResumeOutcome.Resumed -> {
                val details = auditDetails("owner" to session.ownerId, "waitId" to waitId)
                audit.record(OrgAuditRecord(session.orgId, WAKE_ACTOR, WOKEN, SUBJECT, session.id.toString(), details))
                CloudSessionWake.RESUMED
            }
            ResumeOutcome.AtCap -> CloudSessionWake.AT_CAP
            ResumeOutcome.NotFound -> CloudSessionWake.ENDED
        }
    }

    private companion object {
        const val WOKEN = "cloud.session.woken"
        const val SUBJECT = "cloud_session"
    }
}

/** Who resumes a cloud session for the answer to one of its waits, in the audit log: the server, not a person. */
val WAKE_ACTOR: AuthenticatedPrincipal = AuthenticatedPrincipal("cloud-sessions", PrincipalKind.SERVICE, emptySet())
