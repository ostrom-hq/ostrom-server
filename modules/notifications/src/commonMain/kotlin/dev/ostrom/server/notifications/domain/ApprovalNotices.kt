// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.domain

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.identity.domain.Mail

/**
 * The texts that tell an approver a request waits for their yes, on a phone and by e-mail. They name who asks, what
 * and where, never the evidence (the diff, the command's output): the approver reads it in Ostrom, where the yes is
 * given after a fresh proof of who they are.
 *
 * @param webOrigin The console's origin (`OSTROM_WEB_ORIGIN`), without a trailing slash.
 */
class ApprovalNotices(private val webOrigin: String) {
    /**
     * @param gate The gate.
     *
     * @return `<asker> asks for your yes`.
     */
    fun title(gate: Gate): String = "${clip(gate.askedBy.joinToString(", ").ifEmpty { "Someone" })} asks for your yes"

    /**
     * @param gate The gate.
     *
     * @return What the gate covers, and in which repository.
     */
    fun body(gate: Gate): String = clip(listOfNotNull(gate.label, gate.subject.repo).joinToString(" · "))

    /**
     * @param request The approver's request.
     *
     * @return The console's page of the request, where the yes is given (`/yes/{approvalId}`).
     */
    fun link(request: ApprovalRequest): String = "$webOrigin/yes/${request.id}"

    /**
     * @param request The approver's request.
     * @param device The approver's phone.
     *
     * @return The push, which opens the request in the app. Its text is the same for every request: a lock screen
     *   shows it to whoever holds the phone, so it names neither the asker, nor the label, nor the repository.
     */
    fun push(request: ApprovalRequest, device: PushDevice): PushMessage = PushMessage(
        to = device.pushToken,
        title = PUSH_TITLE,
        body = PUSH_BODY,
        data = mapOf("kind" to "approval", "approvalId" to request.id.toString()),
    )

    /**
     * @param gate The gate.
     * @param request The approver's request.
     * @param to The approver's address.
     *
     * @return The e-mail, with the link to the request.
     */
    fun email(gate: Gate, request: ApprovalRequest, to: String): Mail {
        val title = title(gate)
        val body = body(gate)
        val link = link(request)
        val html = """
            <!doctype html>
            <html lang="en">
            <head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${escape(title)}</title></head>
            <body style="margin:0;padding:24px;background:#f6f6f4;font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;color:#1b1b18">
              <table role="presentation" width="100%" style="max-width:480px;margin:0 auto;background:#ffffff;border-radius:8px;padding:32px">
                <tr><td>
                  <p style="margin:0 0 16px;font-size:20px;font-weight:600">${escape(title)}</p>
                  <p style="margin:0 0 24px;font-size:15px;line-height:22px">${escape(body)}</p>
                  <p style="margin:0 0 24px"><a href="${escape(link)}" style="display:inline-block;background:#1b1b18;color:#ffffff;text-decoration:none;padding:12px 20px;border-radius:6px;font-size:15px">Open the request</a></p>
                  <p style="margin:0;font-size:13px;line-height:20px;color:#5c5c56">You answer in Ostrom, after proving it is you. Nothing is approved from this e-mail.</p>
                </td></tr>
              </table>
            </body>
            </html>
        """.trimIndent()
        // A gate with neither label nor repository has no body: the title alone, never a dangling colon.
        val headline = if (body.isEmpty()) title else "$title: $body"
        val text = "$headline.\n\nOpen the request: $link\n\n" +
            "You answer in Ostrom, after proving it is you. Nothing is approved from this e-mail."
        return Mail(to, headline, html, text)
    }
}

/** What every push says: nothing about the request, which the approver reads in the app after proving who they are. */
internal const val PUSH_TITLE = "A request waits for your yes"
internal const val PUSH_BODY = "Open Ostrom to read it."

/** The longest title or text of a notification: a phone shows two lines. */
internal const val MAX_NOTICE_TEXT = 120

/**
 * One line of at most [MAX_NOTICE_TEXT] characters: line breaks and control characters (a label is the asker's text,
 * and it goes into a mail's subject) become spaces, and a cut never splits a character in two (a surrogate pair).
 */
private fun clip(text: String): String {
    val line = text.map { if (it.isISOControl()) ' ' else it }.joinToString("")
    if (line.length <= MAX_NOTICE_TEXT) return line
    val end = if (line[MAX_NOTICE_TEXT - 1].isHighSurrogate()) MAX_NOTICE_TEXT - 1 else MAX_NOTICE_TEXT
    return line.take(end) + "…"
}

private fun escape(value: String): String = buildString {
    value.forEach {
        when (it) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(it)
        }
    }
}
