// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import run.ostrom.server.notifications.domain.Notification
import kotlin.uuid.Uuid

/**
 * What the Slack messages say. A text from a person (a session's name, a gate's label) is escaped, so it never becomes
 * a link or a mention.
 *
 * @param consoleUrl The console's origin, without a trailing slash.
 */
class SlackTexts(private val consoleUrl: String) {
    /**
     * @param notification A notification.
     *
     * @return Its direct message: the title, the text and a link to the wait or the session.
     */
    fun notification(notification: Notification): SlackMessage {
        val link = notification.waitId?.let { "$consoleUrl/waits/${it.pathSafe()}" }
            ?: notification.sessionId?.let { "$consoleUrl/sessions/${it.pathSafe()}" }
        val lines = listOfNotNull(
            "*${escape(notification.title)}*",
            escape(notification.body).ifBlank { null },
            link?.let { "<$it|Open in Ostrom>" },
        )
        return SlackMessage(lines.joinToString("\n"))
    }

    /**
     * @param orgId The organization.
     * @param approvalId The approver's request.
     * @param title Who asks for a yes.
     * @param body What and where.
     * @param link The console's page of the request.
     *
     * @return The yes request: Approve opens the console (a yes needs a fresh proof of who the approver is), Reject
     *   answers no from Slack.
     */
    fun approval(orgId: Uuid, approvalId: Uuid, title: String, body: String, link: String): SlackMessage {
        val text = listOf("*${escape(title)}*", escape(body)).filter { it.isNotBlank() }.joinToString("\n")
        val blocks = buildJsonArray {
            add(section(text))
            addJsonObject {
                put("type", "actions")
                put("block_id", APPROVAL_BLOCK)
                putJsonArray("elements") {
                    addJsonObject {
                        put("type", "button")
                        put("action_id", APPROVE_ACTION)
                        put("style", "primary")
                        put("url", link)
                        putJsonObject("text") { plain("Approve in Ostrom") }
                    }
                    addJsonObject {
                        put("type", "button")
                        put("action_id", REJECT_ACTION)
                        put("style", "danger")
                        put("value", "$orgId:$approvalId")
                        putJsonObject("text") { plain("Reject") }
                    }
                }
            }
            add(context("A yes is given in Ostrom, after proving it is you."))
        }
        return SlackMessage(text, blocks)
    }

    /**
     * @param title The request's title, as it was posted.
     * @param outcome What became of it.
     *
     * @return The yes request without its buttons.
     */
    fun decided(title: String, outcome: String): SlackMessage {
        val text = "*${escape(title)}*\n${escape(outcome)}"
        return SlackMessage(text, buildJsonArray { add(section(text)) })
    }

    /**
     * @param login The member's login.
     * @param orgName Their organization.
     *
     * @return What a Slack account is told once a member links it.
     */
    fun linked(login: String, orgName: String): SlackMessage = SlackMessage(
        "This Slack account now receives the Ostrom notifications of *${escape(login)}* in " +
            "*${escape(orgName)}*. Not you? Remove the link in Ostrom, under your notifications.",
    )

    private fun section(text: String): JsonObject = buildJsonObject {
        put("type", "section")
        putJsonObject("text") {
            put("type", "mrkdwn")
            put("text", text)
        }
    }

    private fun context(text: String): JsonObject = buildJsonObject {
        put("type", "context")
        putJsonArray("elements") {
            addJsonObject {
                put("type", "mrkdwn")
                put("text", text)
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.plain(text: String) {
        put("type", "plain_text")
        put("text", text)
    }

    /** The Block Kit names the interactions read. */
    companion object {
        /** The actions block of a yes request. */
        const val APPROVAL_BLOCK: String = "ostrom_approval"

        /** The Approve button: a link, ignored when Slack reports its click. */
        const val APPROVE_ACTION: String = "ostrom_approve"

        /** The Reject button: its value is `<orgId>:<approvalId>`. */
        const val REJECT_ACTION: String = "ostrom_reject"

        /**
         * @param text A person's text.
         *
         * @return It, with Slack's three control characters escaped.
         */
        fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun String.pathSafe(): String = filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}

