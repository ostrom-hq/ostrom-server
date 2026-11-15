// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

/**
 * Whether the server announces the conversation capabilities (`runners.cloud.conversation.v1` and the keys after it).
 * On: the member routes, the runner channel and the runner's headless host (`OSTROM_SESSION_MODE=conversation`) are
 * all there. A server without it bound announces none.
 *
 * @property announced Announce them.
 */
data class ConversationAnnouncement(val announced: Boolean = true)
