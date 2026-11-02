// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.presentation

import dev.ostrom.server.gates.domain.models.ApprovalInbox
import dev.ostrom.server.gates.domain.models.ApprovalScreen
import dev.ostrom.server.gates.domain.models.CallerRights
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.Viewer
import dev.ostrom.server.gates.domain.models.ViewerChannel
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalChannel

/**
 * Where the caller reads from, as the identity module set the credential: a browser session is the console, the
 * mobile token the app, anything else a program (S2-31); and whether it holds a scope that answers.
 */
internal fun AuthenticatedPrincipal.toViewer() = Viewer(
    channel = when (channel) {
        PrincipalChannel.BROWSER -> ViewerChannel.CONSOLE
        PrincipalChannel.MOBILE_APP -> ViewerChannel.APP
        PrincipalChannel.API_TOKEN -> ViewerChannel.CLI
    },
    // The app's token answers once the phone is unlocked (biometrics, `POST /auth/mobile/unlock`): read before that,
    // the approval says what the person may do, and the answer route asks for the unlock.
    answers = channel == PrincipalChannel.MOBILE_APP || scopes.any { it in GateScopes.answering },
)

/** The contract's `via` of a channel. */
internal fun ViewerChannel.toVia(): String = when (this) {
    ViewerChannel.CONSOLE -> "console"
    ViewerChannel.APP -> "app"
    ViewerChannel.CLI -> "cli"
}

/** Maps the yes queue. */
internal fun ApprovalInbox.toQueue() = ApprovalQueue(
    operator = login,
    you = ApprovalViewer(login, viewer.channel.toVia()),
    pending = pending.map { it.toViewResponse() },
    recent = recent.map { it.toViewResponse() },
)

/** Maps one approval for its reader. */
internal fun ApprovalScreen.toViewResponse(): ApprovalViewResponse {
    val gate = view.gate
    return ApprovalViewResponse(
        approval = view.toApproval(),
        who = ApprovalWho(dev = gate.askedBy.firstOrNull(), session = gate.sessionIds.firstOrNull()),
        what = gate.toWhat(),
        gate = gate.node?.toRef(),
        pr = gate.subject.pullRequest?.let { GatePullRequest(it.number.toString(), it.headSha, it.base) },
        askedVia = gate.askedVia.map { it.name.lowercase() }.sorted(),
        you = rights.toCallerView(),
    )
}

// `repo` is where the command runs; `sessionRepo` the session's, only when it differs (the prototype's `what`).
private fun Gate.toWhat(): ApprovalWhat {
    val real = subject.realRepo
    val declared = subject.repo
    return ApprovalWhat(
        action = label,
        command = subject.command.ifBlank { null },
        repo = real ?: declared,
        sessionRepo = if (real != null && real != declared) declared else null,
        harness = subject.harnessVersion,
    )
}

private fun CallerRights.toCallerView() = ApprovalCallerView(
    login = login,
    self = self,
    canApprove = canApprove,
    reason = reason,
    selfApproval = if (selfApproval) true else null,
)
