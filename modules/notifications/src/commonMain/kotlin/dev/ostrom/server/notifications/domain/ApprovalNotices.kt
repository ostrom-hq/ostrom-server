// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.domain

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.Gate

/**
 * The texts of a request, on a phone and in an email. They name who asks and what, never the evidence: the approver
 * reads it in Ostrom, where the yes is given.
 *
 * @param webOrigin The console's origin (`OSTROM_WEB_ORIGIN`).
 */
class ApprovalNotices(private val webOrigin: String) {
    /**
     * @param gate The gate.
     * @return `<asker> asks for your yes`.
     */
    fun title(gate: Gate): String = "${clip(gate.askedBy.joinToString(", "))} asks for your yes"

    /**
     * @param gate The gate.
     * @return What the gate covers.
     */
    fun body(gate: Gate): String = clip(gate.label)

    /**
     * @param request The request.
     * @return Where it is answered.
     */
    fun link(request: ApprovalRequest): String = "$webOrigin/console/approvals/${request.id}"

    /**
     * @param gate The gate.
     * @param request The approver's request.
     * @param to The approver's phone.
     * @return The push notification, which opens the request in the app.
     */
    fun push(gate: Gate, request: ApprovalRequest, to: String): PushMessage =
        PushMessage(to, title(gate), body(gate), mapOf("kind" to "approval", "approvalId" to request.id.toString()))

    /**
     * @param gate The gate.
     * @param request The approver's request.
     * @param to The approver's address.
     * @return The email, with the link to the request.
     */
    fun email(gate: Gate, request: ApprovalRequest, to: String): EmailMessage = EmailMessage(
        to = to,
        subject = "${title(gate)}: ${body(gate)}",
        text = "${title(gate)}: ${body(gate)}.\n\nAnswer in Ostrom: ${link(request)}\n",
    )

    private fun clip(text: String) = if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "…" else text

    private companion object {
        const val MAX_TEXT = 120
    }
}
