// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import run.ostrom.server.identity.domain.Mail
import run.ostrom.server.kernel.events.GateSummary
import kotlin.uuid.Uuid

/**
 * The texts that tell an approver a request waits for their yes, on a phone and by e-mail. They name who asks, what
 * and where, never the evidence (the diff, the command's output): the approver reads it in Ostrom, where the yes is
 * given after a fresh proof of who they are.
 *
 * @param webOrigin The console's origin (`OSTROM_CONSOLE_URL`), without a trailing slash.
 */
class ApprovalNotices(private val webOrigin: String) {
    /**
     * @param approvalId The approver's request.
     *
     * @return The console's page of the request, where the yes is given (`/yes/{approvalId}`).
     */
    fun link(approvalId: Uuid): String = "$webOrigin/yes/$approvalId"

    /**
     * @param approvalId The approver's request.
     * @param device The approver's phone.
     *
     * @return The push, which opens the request in the app. Its text is the same for every request: a lock screen
     *   shows it to whoever holds the phone, so it names neither the asker, nor the label, nor the repository.
     */
    fun push(approvalId: Uuid, device: PushDevice): PushMessage = PushMessage(
        device = device,
        title = PUSH_TITLE,
        body = PUSH_BODY,
        data = mapOf("kind" to ApprovalRules.KIND, "approvalId" to approvalId.toString()),
    )

    /**
     * @param gate The gate.
     * @param approvalId The approver's request.
     * @param to The approver's address.
     *
     * @return The e-mail, with the link to the request.
     */
    fun email(gate: GateSummary, approvalId: Uuid, to: String): Mail {
        val title = title(gate)
        val body = body(gate)
        val link = link(approvalId)
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

    /** The texts that need no console. */
    companion object {
        /**
         * @param gate The gate.
         *
         * @return `<asker> asks for your yes`.
         */
        fun title(gate: GateSummary): String =
            "${clip(gate.askedBy.joinToString(", ").ifEmpty { "Someone" })} asks for your yes"

        /**
         * @param gate The gate.
         *
         * @return What the gate covers, and in which repository.
         */
        fun body(gate: GateSummary): String = clip(listOfNotNull(gate.label, gate.repo).joinToString(" · "))
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
