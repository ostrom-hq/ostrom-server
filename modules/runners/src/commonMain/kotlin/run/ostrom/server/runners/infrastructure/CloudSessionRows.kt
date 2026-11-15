// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.CloudAttribution
import run.ostrom.server.runners.domain.CloudEnvironmentSpec
import run.ostrom.server.runners.domain.CloudHumanWait
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.DeletionState
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.GitHubAccessMode
import run.ostrom.server.runners.domain.PermissionBypass
import run.ostrom.server.runners.domain.RemoteHost
import run.ostrom.server.runners.domain.RepositorySpec
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionKind
import run.ostrom.server.runners.domain.SessionLimits
import run.ostrom.server.runners.domain.SessionMode
import run.ostrom.server.runners.domain.SessionProfile
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.WorkspaceDeletion
import run.ostrom.server.kernel.database.present
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder

// A session row and the cloud sessions' repository: the states they name, the JSON of the recipe and the profile.

/** The state a paused session is in. */
internal const val PAUSED: String = "paused"

/** The state a session changing owner is in. */
internal val TRANSFERRING: List<String> = listOf("transferring")

/** The recipe's JSON on the row: a field a later version adds is ignored by this one. */
internal val RECIPE_JSON = Json { ignoreUnknownKeys = true }

/** A new session, with every column a creation writes (and a remote session's standing approval, if any). */
internal fun StatementBuilder.sessionInsert(session: CloudSession): InsertStatement<Number> =
    RunnersSessionsTable.insert {
        it[id] = session.id
        it[orgId] = session.orgId
        it[ownerId] = session.ownerId
        it[name] = session.name
        it[agent] = session.agent.wire
        it[model] = session.model
        it[prompt] = session.prompt
        it[repositoryUrl] = session.repository?.url
        it[repositoryRef] = session.repository?.ref
        sessionLifecycle(it, session)
        it[environment] = session.environment?.let { spec ->
            RECIPE_JSON.encodeToString(CloudEnvironmentSpec.serializer(), spec)
        }
        it[profile] = session.profile?.let { spec -> RECIPE_JSON.encodeToString(SessionProfile.serializer(), spec) }
        it[clusterUnreachableSince] = session.clusterUnreachableSince
        it[transferId] = session.transferId
        it[untrustedWorkspace] = session.untrustedWorkspace
        it[expiresAt] = session.expiresAt
        it[terminatedReason] = session.terminatedReason
        it[expiryBackfill] = session.expiryBackfill
        it.deletion(session.workspaceDeletion)
        it[mode] = session.mode.wire
        it[effort] = session.effort
        it[kind] = session.kind.wire
        it[hostDeviceId] = session.host?.deviceId
        it[hostDirectoryName] = session.host?.directoryName
        it[hostPermissionMode] = session.host?.permissionMode
        it[hostFolderId] = session.host?.folderId
        it[createdByInitiatorId] = session.createdBy?.initiatorId
        it[createdByCredentialId] = session.createdBy?.credentialId
        it[createdByEffectiveMemberId] = session.createdBy?.effectiveMemberId
        it[hostResumeSessionId] = session.host?.resumeSessionId
        it[launchId] = session.launchId
        // A remote session in `bypass` mode starts with its owner's standing approval (TeamCode's permissionMode).
        it[bypassBy] = session.permissionBypass?.by
        it[bypassCredentialId] = session.permissionBypass?.credentialId
        it[bypassAt] = session.permissionBypass?.at
    }

/** The lifecycle columns of a new session: its states, its failure, its limits and every instant. */
private fun RunnersSessionsTable.sessionLifecycle(b: UpdateBuilder<*>, session: CloudSession) {
    b[state] = session.state.wire
    b[desiredState] = session.desiredState.wire
    b[phase] = session.phase
    b[epoch] = session.epoch
    b[generation] = 0L
    b[failureCode] = session.failure?.code
    b[failureMessage] = session.failure?.message
    b[failureRetryable] = session.failure?.retryable ?: false
    b[idleTimeoutMinutes] = session.limits.idleTimeoutMinutes
    b[maxRunMinutes] = session.limits.maxRunMinutes
    b[restrictedEgress] = session.limits.restrictedEgress
    b[createdAt] = session.createdAt
    b[updatedAt] = session.updatedAt
    b[executionStartedAt] = session.executionStartedAt
    b[readyAt] = session.readyAt
    b[lastHeartbeatAt] = session.lastHeartbeatAt
    b[lastActivityAt] = session.lastActivityAt
    b[pausedAt] = session.pausedAt
    b[terminatedAt] = session.terminatedAt
    b[pausingSince] = session.pausingSince
    b[runnerStoppedAt] = session.runnerStoppedAt
    b[runnerTokenHash] = session.runnerTokenHash
    b[githubAccess] = session.githubAccess.wire
    b[backgroundSince] = session.backgroundSince
    b[backgroundSeenAt] = session.backgroundSeenAt
}

/** The reconciler's schedule of a new session, and the hash of its runner token. */
internal fun StatementBuilder.sessionRouteInsert(session: CloudSession): InsertStatement<Number> =
    RunnersSessionRoutes.insert {
        it[sessionId] = session.id
        it[orgId] = session.orgId
        it[runnerTokenHash] = session.runnerTokenHash
        it[nextReconcileAt] = session.createdAt
    }

/** The columns of the proof that a workspace is gone, written with the session. */
internal fun UpdateBuilder<*>.deletion(deletion: WorkspaceDeletion?) {
    val s = RunnersSessionsTable
    this[s.workspaceDeletionState] = deletion?.state?.wire
    this[s.workspaceClaimDeletedAt] = deletion?.claimDeletedAt
    this[s.workspaceVolumeDeletedAt] = deletion?.volumeDeletedAt
    this[s.workspaceVolumeName] = deletion?.volumeName
    this[s.workspaceVolumeHandle] = deletion?.volumeHandle
    this[s.workspaceProblemCode] = deletion?.problem?.code
    this[s.workspaceProblemMessage] = deletion?.problem?.message
    this[s.workspaceProblemRetryable] = deletion?.problem?.retryable ?: false
}

/** A session as stored. */
internal fun toSession(row: ResultRow): CloudSession {
    val s = RunnersSessionsTable
    val url = row[s.repositoryUrl]
    val failureCode = row[s.failureCode]
    return CloudSession(
        id = row[s.id],
        orgId = row[s.orgId],
        ownerId = row[s.ownerId],
        name = row[s.name],
        agent = row[s.agent].let { wire -> AgentKind.entries.first { it.wire == wire } },
        repository = url?.let { RepositorySpec(it, row[s.repositoryRef]) },
        state = SessionState.fromWire(row[s.state]),
        desiredState = DesiredState.fromWire(row[s.desiredState]),
        phase = row[s.phase],
        epoch = row[s.epoch],
        generation = row[s.generation],
        failure = failureCode?.let {
            SessionFailure(it, row.present(s.failureMessage, s.failureCode), row[s.failureRetryable])
        },
        limits = SessionLimits(
            idleTimeoutMinutes = row[s.idleTimeoutMinutes],
            maxRunMinutes = row[s.maxRunMinutes],
            restrictedEgress = row[s.restrictedEgress],
        ),
        createdAt = row[s.createdAt],
        updatedAt = row[s.updatedAt],
        executionStartedAt = row[s.executionStartedAt],
        readyAt = row[s.readyAt],
        lastHeartbeatAt = row[s.lastHeartbeatAt],
        lastActivityAt = row[s.lastActivityAt],
        pausedAt = row[s.pausedAt],
        terminatedAt = row[s.terminatedAt],
        pausingSince = row[s.pausingSince],
        runnerStoppedAt = row[s.runnerStoppedAt],
        runnerTokenHash = row[s.runnerTokenHash],
        backgroundSince = row[s.backgroundSince],
        backgroundSeenAt = row[s.backgroundSeenAt],
        model = row[s.model],
        prompt = row[s.prompt],
        // A name this version does not know grants nothing.
        githubAccess = GitHubAccessMode.fromWire(row[s.githubAccess]) ?: GitHubAccessMode.NONE,
        environment = row[s.environment]
            ?.let { RECIPE_JSON.decodeFromString(CloudEnvironmentSpec.serializer(), it) },
        profile = row[s.profile]?.let { RECIPE_JSON.decodeFromString(SessionProfile.serializer(), it) },
        clusterUnreachableSince = row[s.clusterUnreachableSince],
        transferId = row[s.transferId],
        untrustedWorkspace = row[s.untrustedWorkspace],
        expiresAt = row[s.expiresAt],
        expiryBackfill = row[s.expiryBackfill],
        terminatedReason = row[s.terminatedReason],
        // A mode this version does not know is a terminal: it never takes a message it cannot deliver.
        mode = SessionMode.fromWire(row[s.mode]) ?: SessionMode.TERMINAL,
        effort = row[s.effort],
        permissionBypass = bypassOf(row),
        waiting = waitOf(row),
        workspaceDeletion = deletionOf(row),
        // A kind this version does not know is a cloud session's: it never hosts it on a device.
        kind = SessionKind.fromWire(row[s.kind]) ?: SessionKind.CLOUD,
        host = hostOf(row),
        createdBy = createdByOf(row),
        launchId = row[s.launchId],
    )
}

// Recorded since runners.cloud.onbehalf.v1: an older session has none, nor a row missing either member.
private fun createdByOf(row: ResultRow): CloudAttribution? {
    val s = RunnersSessionsTable
    val initiator = row[s.createdByInitiatorId]
    val effective = row[s.createdByEffectiveMemberId]
    if (initiator == null || effective == null) return null
    return CloudAttribution(initiator, row[s.createdByCredentialId], effective)
}

private fun hostOf(row: ResultRow): RemoteHost? {
    val s = RunnersSessionsTable
    return row[s.hostPermissionMode]?.let {
        RemoteHost(
            deviceId = row[s.hostDeviceId],
            directoryName = row[s.hostDirectoryName],
            permissionMode = it,
            folderId = row[s.hostFolderId],
            resumeSessionId = row[s.hostResumeSessionId],
        )
    }
}

private fun bypassOf(row: ResultRow): PermissionBypass? {
    val s = RunnersSessionsTable
    return row[s.bypassBy]?.let { PermissionBypass(it, row[s.bypassCredentialId], row.present(s.bypassAt, s.bypassBy)) }
}

private fun waitOf(row: ResultRow): CloudHumanWait? {
    val s = RunnersSessionsTable
    return row[s.waitingReason]?.let {
        CloudHumanWait(
            reason = it,
            requestId = row.present(s.waitingRequestId, s.waitingReason),
            since = row.present(s.waitingSince, s.waitingReason),
            eventSeq = row.present(s.waitingEventSeq, s.waitingReason),
            epoch = row.present(s.waitingEpoch, s.waitingReason),
        )
    }
}

private fun deletionOf(row: ResultRow): WorkspaceDeletion? {
    val s = RunnersSessionsTable
    val state = row[s.workspaceDeletionState] ?: return null
    val problem = row[s.workspaceProblemCode]?.let {
        SessionFailure(
            it,
            row.present(s.workspaceProblemMessage, s.workspaceProblemCode),
            row[s.workspaceProblemRetryable],
        )
    }
    return WorkspaceDeletion(
        state = DeletionState.fromWire(state),
        claimDeletedAt = row[s.workspaceClaimDeletedAt],
        volumeDeletedAt = row[s.workspaceVolumeDeletedAt],
        volumeName = row[s.workspaceVolumeName],
        volumeHandle = row[s.workspaceVolumeHandle],
        problem = problem,
    )
}
