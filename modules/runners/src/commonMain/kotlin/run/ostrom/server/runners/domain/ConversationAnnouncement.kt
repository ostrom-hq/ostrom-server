// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

/**
 * Whether the server announces the conversation capabilities (`runners.cloud.conversation.v1` and the keys after it).
 * Off until the runner side of the conversation lands: the routes are served, but no client is told to use them.
 *
 * @property announced Announce them.
 */
data class ConversationAnnouncement(val announced: Boolean = false)
