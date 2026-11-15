// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import run.ostrom.server.notifications.domain.Notification
import kotlin.uuid.Uuid

/**
 * What the Discord messages say. A text from a person (a session's name, a gate's label) is escaped, so it never
 * becomes markdown or a link; the messages never mention anyone (the API call sends no `allowed_mentions` parse).
 *
 * @param consoleUrl The console's origin, without a trailing slash.
 */
class DiscordTexts(private val consoleUrl: String) {
    /**
     * @param notification A notification.
     *
     * @return Its direct message: the title, the text and a link to the wait or the session.
     */
    fun notification(notification: Notification): DiscordMessage {
        val waitId = notification.waitId
        val sessionId = notification.sessionId
        val link = when {
            waitId != null -> "$consoleUrl/waits/${waitId.pathSafe()}"
            sessionId != null -> "$consoleUrl/sessions/${sessionId.pathSafe()}"
            else -> null
        }
        val lines = listOfNotNull(
            "**${escape(notification.title)}**",
            escape(notification.body).ifBlank { null },
            // In angle brackets: Discord shows no preview of our own page.
            link?.let { "<$it>" },
        )
        return DiscordMessage(fit(lines.joinToString("\n")))
    }

    /**
     * @param orgId The organization.
     * @param approvalId The approver's request.
     * @param title Who asks for a yes.
     * @param body What and where.
     * @param link The console's page of the request.
     *
     * @return The yes request: Approve opens the console (a yes needs a fresh proof of who the approver is), Reject
     *   answers no from Discord.
     */
    fun approval(orgId: Uuid, approvalId: Uuid, title: String, body: String, link: String): DiscordMessage {
        val text = listOf("**${escape(title)}**", escape(body), "-# A yes is given in Ostrom, after proving it is you.")
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val components = buildJsonArray {
            addJsonObject {
                put("type", ACTION_ROW)
                putJsonArray("components") {
                    addJsonObject {
                        put("type", BUTTON)
                        put("style", LINK_STYLE)
                        put("label", "Approve in Ostrom")
                        put("url", link)
                    }
                    addJsonObject {
                        put("type", BUTTON)
                        put("style", DANGER_STYLE)
                        put("label", "Reject")
                        put("custom_id", rejectId(orgId, approvalId))
                    }
                }
            }
        }
        return DiscordMessage(fit(text), components)
    }

    /**
     * @param title The request's title, as it was posted.
     * @param outcome What became of it.
     *
     * @return The yes request without its buttons.
     */
    fun decided(title: String, outcome: String): DiscordMessage =
        DiscordMessage(fit("**${escape(title)}**\n${escape(outcome)}"), JsonArray(emptyList()))

    /**
     * @param login The member's login.
     * @param orgName Their organization.
     *
     * @return What a Discord account is told once a member links it.
     */
    fun linked(login: String, orgName: String): DiscordMessage = DiscordMessage(
        fit(
            "This Discord account now receives the Ostrom notifications of **${escape(login)}** in " +
                "**${escape(orgName)}**. Not you? Remove the link in Ostrom, under your notifications.",
        ),
    )

    /** The Discord names the interactions read. */
    companion object {
        /** The prefix of the Reject button's `custom_id`: `ostrom_reject:<orgId>:<approvalId>` (86 characters). */
        const val REJECT_PREFIX: String = "ostrom_reject:"

        /** Discord's limit of a message's content. */
        const val MAX_CONTENT: Int = 2000

        private const val ACTION_ROW = 1
        private const val BUTTON = 2
        private const val DANGER_STYLE = 4
        private const val LINK_STYLE = 5
        private val MARKDOWN = setOf('\\', '*', '_', '~', '`', '|', '>', '#', '-', '[', ']', '(', ')', '<', '@', ':')

        /**
         * @param orgId The organization.
         * @param approvalId The approver's request.
         *
         * @return The Reject button's `custom_id`.
         */
        fun rejectId(orgId: Uuid, approvalId: Uuid): String = "$REJECT_PREFIX$orgId:$approvalId"

        /**
         * @param text A person's text.
         *
         * @return It, with every character Discord's markdown reads escaped, on one line per line.
         */
        fun escape(text: String): String = buildString {
            for (c in text) {
                if (c in MARKDOWN) append('\\')
                append(c)
            }
        }

        /**
         * @param text A message.
         *
         * @return It, cut to Discord's limit.
         */
        fun fit(text: String): String = if (text.length <= MAX_CONTENT) text else text.take(MAX_CONTENT - 1) + "…"

        /**
         * @param configured `OSTROM_CONSOLE_URL`, as configured.
         *
         * @return The console's origin, without a trailing slash; [DEFAULT_CONSOLE_URL] when unset or blank.
         */
        fun consoleUrl(configured: String?): String =
            configured.orEmpty().trim().ifEmpty { DEFAULT_CONSOLE_URL }.trimEnd('/')

        /** The console of a development server, as the notifications module defaults it. */
        const val DEFAULT_CONSOLE_URL: String = "http://localhost:5173"

        private fun String.pathSafe(): String = filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
