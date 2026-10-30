// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.domain.models

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException

/** The gate or request does not exist, or belongs to another organization (the same answer, so nothing leaks). */
class GateNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Gate not found.")

/** The caller is not the approver of this request: an admin never answers in an approver's place. */
class NotTheApproverException :
    OstromException(ErrorKind.FORBIDDEN, "Only the approver this request was sent to can answer it.")

/** A yes from a sign-in older than [StepUp.WITHIN], or of unknown age (S2-54). */
class StepUpRequiredException : OstromException(
    ErrorKind.FORBIDDEN,
    "Sign in again to say yes: a yes needs a sign-in of less than 5 minutes, or the app's biometrics.",
)

/** A device, agent or service token tried to answer: only a signed-in person says yes or no. */
class OnlyPeopleAnswerException : OstromException(ErrorKind.FORBIDDEN, "Only a signed-in person can answer a gate.")

/**
 * The person who asked for the action tried to approve it.
 *
 * @property others The approvers who can say yes instead; empty when nobody else on the gate can.
 */
class SelfApprovalRefusedException(val others: List<String>) : OstromException(
    ErrorKind.FORBIDDEN,
    if (others.isEmpty()) {
        "You asked for this action and cannot approve it, and nobody else on this gate can: " +
            "add another approver to the gate."
    } else {
        "You asked for this action and cannot approve it: ${others.joinToString(" or ")} must say yes."
    },
)

/** The gate or request was already answered: a yes is given once. */
class GateAlreadyDecidedException : OstromException(ErrorKind.CONFLICT, "This gate was already answered.")

/** What the approver saw is not what the gate covers now: the yes is bound to what was shown. */
class GateChangedException : OstromException(
    ErrorKind.CONFLICT,
    "What this gate covers changed since you opened it. Reload it and answer again.",
)

/** The gate has no approver: nobody can open it, so it is refused before anyone waits. */
class GateWithoutApproverException : OstromException(
    ErrorKind.CONFLICT,
    "This gate has no approver. Add one to the gate node of the workflow.",
)

/** The yes cannot be reserved or consumed: it was not given, it is used up, or another session holds it. */
class GateNotUsableException(reason: String) : OstromException(ErrorKind.CONFLICT, reason)

/** The caller's session did not ask for this gate: a yes serves the sessions that asked for it, nobody else. */
class NotYourGateException : OstromException(ErrorKind.FORBIDDEN, "This gate was not asked by your session.")

/** The credential belongs to no person (a key or an agent its resolver could not tie to one): it asks no gate. */
class NoPersonException : OstromException(
    ErrorKind.FORBIDDEN,
    "This credential belongs to no person: a gate is asked on behalf of a person.",
)

/**
 * The push rewrites where git reads or sends (`GIT_DIR`, `--git-dir`, `-c remote.*`…): Ostrom cannot tie a yes to
 * what it would really send, so no yes is asked.

 */
class RedirectedPushException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "This push changes where git reads or sends (GIT_DIR, --git-dir, --work-tree, GIT_CONFIG_*, -c remote.*, " +
        "-c url.*): Ostrom could not tie the yes to what it really sends, and no yes was asked. Run a plain " +
        "`git push <remote> <branch>` from the repository, as its own command.",
)

/** A hook from before 29/10 asks a yes for a push without the commits it sends: the yes could not be tied to them. */
class OutdatedLauncherException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "This Ostrom launcher is out of date: it asks a yes for a push without sending which commits the push sends, so " +
        "the yes could not be tied to them, and no yes was asked. Update the launcher (`ostrom`, and the hooks " +
        "written by `ostrom sync`), then run the same push again.",
)

/**
 * An enforced merge whose head and base GitHub did not give: its yes would be bound to the command's text, and commits
 * pushed after the yes would be merged under it (audit S2-06, A8). Refused until the GitHub integration answers.
 */
class GitHubRequiredException : OstromException(
    ErrorKind.UNAVAILABLE,
    "GitHub integration required: Ostrom ties a merge's yes to the pull request's head and base, read from GitHub, " +
        "and could not read them for this pull request. No yes was asked. Install the Ostrom GitHub App on this " +
        "repository, or check that the pull request exists, then run the same merge again.",
)

/** An enforced merge sent without its pull request number: nothing but the text would tie the yes (audit S2-06). */
class MissingPullRequestException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "This merge names no pull request number, so Ostrom could not tie the yes to the pull request's head, and no " +
        "yes was asked. Merge with `gh pr merge <number>`, and update the launcher if it is the one that sent this.",
)

/** A field of the request is out of bounds (too long, malformed SHA, too many refs). */

class InvalidGateRequestException(reason: String) : OstromException(ErrorKind.INVALID_REQUEST, reason)
