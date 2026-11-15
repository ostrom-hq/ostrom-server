// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack

import run.ostrom.server.integrations.slack.domain.SlackApi
import run.ostrom.server.integrations.slack.domain.SlackApprovalMessage
import run.ostrom.server.integrations.slack.domain.SlackApprovalMessagesRepository
import run.ostrom.server.integrations.slack.domain.SlackCallException
import run.ostrom.server.integrations.slack.domain.SlackLinksRepository
import run.ostrom.server.integrations.slack.domain.SlackMember
import run.ostrom.server.integrations.slack.domain.SlackMessage
import run.ostrom.server.integrations.slack.domain.SlackPeople
import run.ostrom.server.integrations.slack.domain.SlackPosted
import run.ostrom.server.integrations.slack.domain.SlackSettings
import run.ostrom.server.integrations.slack.domain.SlackUser
import run.ostrom.server.integrations.slack.domain.StoredSlackLink
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsValues
import kotlin.uuid.Uuid

/** A bot token the settings accept. */
const val BOT_TOKEN = "xoxb-1111-2222-abcdefabcdef"

/** A signing secret the settings accept. */
const val SIGNING_SECRET = "8f742231b10e8888abcd99yyyzzz85a5"

/** The instance's settings with the Slack app stored, or not while [configured] is false; sign-up open. */
class SlackInstanceSettings(var configured: Boolean = true) : InstanceSettings {
    override suspend fun read(section: String): SettingsValues = when {
        section == SlackSettings.NAME && configured -> SettingsValues(
            mapOf(SlackSettings.BOT_TOKEN to BOT_TOKEN, SlackSettings.SIGNING_SECRET to SIGNING_SECRET),
        )
        section == "sign-up" -> SettingsValues(mapOf("mode" to "open"))
        else -> SettingsValues.EMPTY
    }
}

/** Slack as a test wants it: [users] by address, [failure] thrown by every call while set. */
class FakeSlackApi(private val users: Map<String, SlackUser> = emptyMap()) : SlackApi {
    var failure: SlackCallException? = null
    var postFailure: SlackCallException? = null
    val posted = mutableListOf<Pair<String, SlackMessage>>()
    val updated = mutableListOf<Pair<SlackPosted, SlackMessage>>()
    val lookedUp = mutableListOf<String>()

    override suspend fun authTest(token: String): String? = if (token == BOT_TOKEN) null else "invalid_auth"

    override suspend fun lookupByEmail(token: String, email: String): SlackUser? {
        failure?.let { throw it }
        lookedUp += email
        return users[email]
    }

    override suspend fun postMessage(token: String, userId: String, message: SlackMessage): SlackPosted {
        (failure ?: postFailure)?.let { throw it }
        posted += userId to message
        return SlackPosted("D-$userId", "1700000000.${posted.size}")
    }

    override suspend fun updateMessage(token: String, posted: SlackPosted, message: SlackMessage) {
        failure?.let { throw it }
        updated += posted to message
    }
}

/** The links, in memory. */
class InMemorySlackLinks : SlackLinksRepository {
    val links = mutableMapOf<Pair<Uuid, Uuid>, StoredSlackLink>()

    override suspend fun of(orgId: Uuid, memberId: Uuid): StoredSlackLink? = links[orgId to memberId]

    override suspend fun ofMembers(orgId: Uuid, memberIds: Set<Uuid>): List<StoredSlackLink> =
        memberIds.mapNotNull { links[orgId to it] }

    override suspend fun memberOf(orgId: Uuid, slackUserId: String): Uuid? =
        links.entries.firstOrNull { it.key.first == orgId && it.value.slackUserId == slackUserId }?.key?.second

    override suspend fun save(orgId: Uuid, link: StoredSlackLink) {
        links[orgId to link.memberId] = link
    }

    override suspend fun remove(orgId: Uuid, memberId: Uuid) {
        links.remove(orgId to memberId)
    }

    override suspend fun deleteAll(orgId: Uuid) {
        links.keys.removeAll { it.first == orgId }
    }
}

/** The yes requests' messages, in memory. */
class InMemorySlackMessages : SlackApprovalMessagesRepository {
    val messages = mutableMapOf<Pair<Uuid, Uuid>, SlackApprovalMessage>()

    override suspend fun save(orgId: Uuid, message: SlackApprovalMessage) {
        messages[orgId to message.approvalId] = message
    }

    override suspend fun ofGate(orgId: Uuid, gateId: Uuid): List<SlackApprovalMessage> =
        messages.filter { it.key.first == orgId && it.value.gateId == gateId }.values.toList()

    override suspend fun deleteAll(orgId: Uuid) {
        messages.keys.removeAll { it.first == orgId }
    }
}

/** The members a test knows. */
class MapSlackPeople(private val members: Map<Uuid, SlackMember>) : SlackPeople {
    override suspend fun member(orgId: Uuid, memberId: Uuid): SlackMember? = members[memberId]
}
