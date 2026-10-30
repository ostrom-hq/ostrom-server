// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack.domain

import dev.ostrom.server.gates.domain.models.Evidence
import dev.ostrom.server.gates.domain.models.Gate

/**
 * Which gates a yes on Slack may open (SECURITY-AUDIT S2-31, S2-04): a yes is given from a browser session or the
 * mobile app, and Slack is the one exception, for a low risk push only. Low risk: a push that is not forced, whose
 * diff, as the developer's machine sent it, changes at most [MAX_CHANGED_LINES] lines. A merge, a force-push, a push
 * without a diff or with a bigger one is answered in Ostrom; its message says so and carries no button.
 */
object SlackRisk {
    /** The most changed lines a yes on Slack may cover. */
    const val MAX_CHANGED_LINES: Int = 15

    /**
     * @param gate The gate, as stored (never as a button says).
     * @return True when a yes on Slack may open it.
     */
    fun answerable(gate: Gate): Boolean {
        val push = gate.subject.push
        val diff = gate.evidence.firstOrNull { it.label == Evidence.MACHINE_DIFF }
        return push != null && diff != null && !push.force && changedLines(diff.value) in 1..MAX_CHANGED_LINES
    }

    /**
     * Counts the added and removed lines of a unified diff (file headers `+++` and `---` excluded).
     *
     * @param diff The diff.
     * @return The count.
     */
    fun changedLines(diff: String): Int = diff.lineSequence().count { line ->
        val changed = line.startsWith('+') || line.startsWith('-')
        changed && !line.startsWith("+++") && !line.startsWith("---")
    }
}
