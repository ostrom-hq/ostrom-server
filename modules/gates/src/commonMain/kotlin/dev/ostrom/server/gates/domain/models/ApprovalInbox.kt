// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.domain.models

/** Where the person reading an approval holds their credential: only the console and the app say yes (S2-31). */
enum class ViewerChannel {
    /** The console's browser session. */
    CONSOLE,

    /** The mobile app, unlocked with the phone's biometrics. */
    APP,

    /** A token any program can present (the CLI's, a personal token): it reads, it never says yes. */
    CLI,
}

/**
 * Who reads an approval, as their credential says.
 *
 * @property channel Where the credential is held.
 * @property answers True when the credential answers approvals: it holds the scope, or it is the app's, which answers
 *   once the phone is unlocked. A read-only credential never does.
 */
data class Viewer(val channel: ViewerChannel, val answers: Boolean)

/**
 * What the reader of an approval may do with it now: what the answer route would say to their yes.
 *
 * @property login The person a yes would be signed as.
 * @property self True when that person asked for the action.
 * @property canApprove True when their yes would pass, the fresh sign-in or the app's unlock aside (the answer route
 *   asks for them, `step_up_required`).
 * @property reason Why their yes would be refused, in plain words; null when it would pass.
 * @property selfApproval True when their yes would pass as a self-approval: they asked, and they are the only approver.
 */
data class CallerRights(
    val login: String,
    val self: Boolean,
    val canApprove: Boolean,
    val reason: String? = null,
    val selfApproval: Boolean = false,
)

/**
 * One approval as its reader sees it: the request, its gate, and what the reader may do with it.
 *
 * @property view The request and its gate.
 * @property rights What the reader may do.
 */
data class ApprovalScreen(val view: ApprovalView, val rights: CallerRights)

/**
 * The yes queue of a person: what waits for their answer, and what they were asked lately.
 *
 * @property login The person a yes is signed as.
 * @property viewer Where they read it from.
 * @property pending Their requests still waiting, newest first.
 * @property recent Their requests already answered or closed, newest first.
 */
data class ApprovalInbox(
    val login: String,
    val viewer: Viewer,
    val pending: List<ApprovalScreen>,
    val recent: List<ApprovalScreen>,
)

/**
 * What a reader may do with an approval, computed with the rules the answer route applies, in its order: where the
 * credential is held and its scope (S2-31), the approver of the request, a gate still waiting, then the self-approval
 * rule (S2-15). The fresh sign-in of a yes (S2-54) is left out: the answer route asks for it when it is stale.
 */
object ApprovalRights {
    /** The answer route's refusal of a yes from anything but the browser or the app. */
    const val NOT_HERE: String = "A yes is given in the browser or in the app."

    /** A credential that may read approvals but not answer them. */
    const val READ_ONLY: String = "This credential reads approvals but does not answer them."

    /**
     * Tells what [person] may do with [view].
     *
     * @param person The person a yes would be signed as.
     * @param viewer Where they read it from.
     * @param view The request and its gate.
     * @param approvers Every approver of the gate, in their order.
     * @param policy The organization's self-approval setting.
     * @return The rights, with the reason of a refusal.
     */
    fun of(
        person: String,
        viewer: Viewer,
        view: ApprovalView,
        approvers: List<String>,
        policy: SelfApprovalPolicy,
    ): CallerRights {
        val gate = view.gate
        val self = gate.askedBy.any { SelfApproval.same(it, person) }
        val refused = when {
            viewer.channel == ViewerChannel.CLI -> NOT_HERE
            !viewer.answers -> READ_ONLY
            !SelfApproval.same(view.request.approverId, person) -> NotTheApproverException().message
            gate.status != GateStatus.PENDING -> GateAlreadyDecidedException().message
            view.request.status != RequestStatus.PENDING -> GateAlreadyDecidedException().message
            else -> null
        }
        if (refused != null) return CallerRights(person, self, canApprove = false, reason = refused)
        return when (val verdict = SelfApproval.check(gate.askedBy, approvers, person, Answer.YES, policy)) {
            is SelfApproval.Verdict.Refused -> {
                val reason = SelfApprovalRefusedException(verdict.others).message
                CallerRights(person, self, canApprove = false, reason = reason)
            }
            is SelfApproval.Verdict.Allowed ->
                CallerRights(person, self, canApprove = true, selfApproval = verdict.selfApproved)
        }
    }
}
