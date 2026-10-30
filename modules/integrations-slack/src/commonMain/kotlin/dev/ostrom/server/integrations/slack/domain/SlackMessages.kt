// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack.domain

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The messages of a request: what the gate covers, as the approver sees it in Ostrom, then two buttons when a yes on
 * Slack may open it ([SlackRisk]) or a link to answer in Ostrom. A button carries the request and the digest of what
 * was shown, never a decision on its own: the server checks both at the click.
 *
 * @param webOrigin The console's address, for the link.
 */
class SlackMessages(private val webOrigin: String) {
    /**
     * @param gate The gate.
     * @param request The approver's request.
     * @return The message.
     */
    fun request(gate: Gate, request: ApprovalRequest): SlackText {
        val title = "${gate.askedBy.joinToString(", ")} asks for your yes: ${gate.label}"
        val blocks = buildJsonArray {
            add(section("*${escape(title)}*"))
            gate.evidence.take(MAX_LINES).forEach { add(section("*${escape(it.label)}*\n${escape(clip(it.value))}")) }
            if (SlackRisk.answerable(gate)) {
                add(buttons(request.id.toString(), gate.shownDigest))
            } else {
                add(section("Answer in Ostrom: ${link(request)}. A yes on Slack covers a small push only."))
            }
        }
        return SlackText(title, blocks)
    }

    /**
     * @param gate The decided gate.
     * @return The message that replaces a request's once the gate is decided: no button any more.
     */
    fun decided(gate: Gate): SlackText {
        // Only an answered gate is told decided: refused, or approved (then perhaps already reserved or used).
        val outcome = if (gate.status == GateStatus.REJECTED) "refused" else "approved"
        val text = "${gate.label}: $outcome by ${gate.decidedBy}"
        return SlackText(text, buildJsonArray { add(section(escape(text))) })
    }

    /**
     * @param request The request.
     * @param why Why the click did not answer it.
     * @return The message that replaces the request's after a refused click.
     */
    fun refused(request: String, why: String): SlackText {
        val text = "$why Answer in Ostrom: $webOrigin/console/approvals/$request"
        return SlackText(text, buildJsonArray { add(section(escape(text))) })
    }

    private fun link(request: ApprovalRequest) = "<$webOrigin/console/approvals/${request.id}|open the request>"

    private fun section(markdown: String): JsonObject = buildJsonObject {
        put("type", "section")
        putJsonObject("text") {
            put("type", "mrkdwn")
            put("text", markdown)
        }
    }

    private fun buttons(requestId: String, shownDigest: String): JsonObject = buildJsonObject {
        put("type", "actions")
        putJsonArray("elements") {
            addJsonObject { button("Approve", YES, "primary", "$requestId:$shownDigest") }
            addJsonObject { button("Refuse", NO, "danger", "$requestId:$shownDigest") }
        }
    }

    private fun JsonObjectBuilder.button(
        label: String,
        action: String,
        style: String,
        value: String,
    ) {
        put("type", "button")
        put("action_id", action)
        put("style", style)
        put("value", value)
        putJsonObject("text") {
            put("type", "plain_text")
            put("text", label)
        }
    }

    // A value is shown as text: Slack's control characters cannot open a link or mention someone.
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun clip(text: String) = if (text.length > MAX_VALUE) text.take(MAX_VALUE) + "…" else text

    /** The action ids of the buttons. */
    companion object {
        /** The yes button. */
        const val YES: String = "ostrom_yes"

        /** The no button. */
        const val NO: String = "ostrom_no"

        private const val MAX_LINES = 8
        private const val MAX_VALUE = 1_500
    }
}
