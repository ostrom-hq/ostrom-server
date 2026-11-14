// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A member's GitHub access, as the runner hands it to `git` and `gh` (the integrations-github module binds it on the
 * members' GitHub links; without it, nobody is linked).
 */
interface RunnerGitHubTokens {
    /** @return Whether members can link a GitHub account on this server. */
    suspend fun available(): Boolean

    /**
     * @param orgId The organization.
     * @param memberId The member a session runs for.
     * @return Their access: a token valid for a while, or why there is none.
     */
    suspend fun token(orgId: Uuid, memberId: String): RunnerGitHubToken
}

/** Nobody is linked: a server built without the GitHub integration. */
object NoRunnerGitHubTokens : RunnerGitHubTokens {
    override suspend fun available(): Boolean = false

    override suspend fun token(orgId: Uuid, memberId: String): RunnerGitHubToken = RunnerGitHubToken.NotLinked
}

/** What the runner gets for one Git or `gh` operation. */
sealed interface RunnerGitHubToken {
    /** The stable name in the API. */
    val wire: String

    /**
     * A token of the owner's linked account.
     *
     * @property token The user-to-server token, never logged.
     * @property expiresAt When it expires, null when the App does not expire user tokens.
     * @property login The GitHub account.
     * @property userId The GitHub account's numeric id, for its no-reply address; null when unknown.
     */
    class Linked(
        val token: String,
        val expiresAt: Instant?,
        val login: String,
        val userId: Long? = null,
    ) : RunnerGitHubToken {
        override val wire: String = "linked"

        override fun toString(): String = "Linked($login, expiresAt=$expiresAt)"
    }

    /** No link, or the session's access is `none`: public repositories only. */
    data object NotLinked : RunnerGitHubToken {
        override val wire: String = "not_linked"
    }

    /** GitHub could not refresh the token for now: try again later. */
    data object Unavailable : RunnerGitHubToken {
        override val wire: String = "unavailable"
    }
}

/**
 * The runner of an execution asking for its owner's GitHub token (`POST /api/v1/cloud/runner/github`).
 *
 * @property sessionId The session the runner was started for.
 * @property epoch The execution the runner was started for.
 */
data class RunnerGitHubRequest(val sessionId: Uuid, val epoch: Long)

/** Hands the runner of a current execution its owner's GitHub token, as the session's access allows. */
interface RunnerGitHubTokenUseCase {
    /**
     * @param token The runner token, as presented.
     * @param request The session and execution the runner names.
     * @return The access.
     * @throws RunnerUnauthenticatedException When no current execution holds the token.
     * @throws ExecutionFencedException When the token is not the one of the session and execution named, or the
     *   execution is ending.
     */
    suspend operator fun invoke(token: String, request: RunnerGitHubRequest): RunnerGitHubToken
}

/**
 * [RunnerGitHubTokenUseCase] on the repository and the members' links.
 *
 * @param repository The sessions.
 * @param tokens Hashes the presented token.
 * @param github The members' GitHub access.
 */
class RunnerGitHubTokenUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val tokens: RunnerTokens,
    private val github: RunnerGitHubTokens,
) : RunnerGitHubTokenUseCase {
    override suspend fun invoke(token: String, request: RunnerGitHubRequest): RunnerGitHubToken {
        val session = repository.findByRunnerTokenHash(tokens.hash(token)) ?: throw RunnerUnauthenticatedException()
        // Only the current execution, while it runs or starts (the clone comes before ready), and not one being
        // deleted: a token never outlives the execution it was asked for by much.
        val current = session.id == request.sessionId && session.epoch == request.epoch &&
            session.state.holdsExecution && session.desiredState != DesiredState.TERMINATED
        if (!current) throw ExecutionFencedException()
        if (session.githubAccess == GitHubAccessMode.NONE) return RunnerGitHubToken.NotLinked
        return github.token(session.orgId, session.ownerId)
    }
}

/** Sets whose GitHub access one of the caller's sessions uses. */
interface SetCloudSessionGitHubAccessUseCase {
    /**
     * @param caller The owner.
     * @param principal Their credential, for the audit log.
     * @param id The session.
     * @param access The mode's wire name.
     * @return The session after the change.
     * @throws GitHubOwnerOnlyException For an agent or a service key.
     * @throws InvalidCloudSessionRequestException For an unknown mode.
     * @throws CloudSessionNotFoundException When it does not exist or is not the caller's.
     * @throws CloudSessionStateException When the session is over.
     * @throws SessionNotPausedException For `none` while an execution holds credentials.
     */
    suspend operator fun invoke(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        id: Uuid,
        access: String,
    ): CloudSession
}

/**
 * [SetCloudSessionGitHubAccessUseCase] on the repository.
 *
 * @param repository The sessions.
 * @param audit The organization's audit log.
 * @param clock The time.
 */
class SetCloudSessionGitHubAccessUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
) : SetCloudSessionGitHubAccessUseCase {
    @Suppress("ThrowsCount") // Each refusal of the change is its own answer.
    override suspend fun invoke(
        caller: Caller,
        principal: AuthenticatedPrincipal,
        id: Uuid,
        access: String,
    ): CloudSession {
        // An agent could lend itself its owner's token, a service key acts for nobody in person.
        if (principal.kind != PrincipalKind.USER) throw GitHubOwnerOnlyException()
        val mode = GitHubAccessMode.fromWire(access)
            ?: throw InvalidCloudSessionRequestException("The access must be owner, shared or none.")
        val session = repository.ownedBy(caller, id)
        if (session.state.isFinal) throw CloudSessionStateException("This session is over.")
        if (session.githubAccess == mode) return session
        // A running execution's `gh` keeps the token it was handed until it expires: revoke the access paused.
        if (mode == GitHubAccessMode.NONE && session.state != SessionState.PAUSED) throw SessionNotPausedException()
        // `none` is written only if the session is still paused: a resume in between keeps it as it was.
        val changed = repository.setGitHubAccess(caller.orgId, id, mode, clock.now())
            ?: throw if (mode == GitHubAccessMode.NONE) SessionNotPausedException() else CloudSessionNotFoundException()
        audit.record(
            OrgAuditRecord(
                caller.orgId,
                principal,
                ACCESS_CHANGED,
                "cloud_session",
                id.toString(),
                auditDetails("from" to session.githubAccess.wire, "to" to mode.wire),
            ),
        )
        return changed
    }

    private companion object {
        const val ACCESS_CHANGED = "cloud.session.github_access_changed"
    }
}
