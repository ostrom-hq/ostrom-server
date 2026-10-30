// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack.presentation

import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.gates.presentation.requireAnyScope
import dev.ostrom.server.integrations.slack.domain.SlackClick
import dev.ostrom.server.integrations.slack.domain.SlackLink
import dev.ostrom.server.integrations.slack.domain.SlackMessages
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requireOrgPrincipal
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * The Slack routes: the interactions (signed by Slack, no credential: the signature, then the link of the Slack
 * member, are the proof) and the organization's links (a browser session of an owner or an admin).
 *
 * @param dependencies The use cases.
 */
fun Route.slackRoutes(dependencies: SlackRoutesDependencies) = with(dependencies) {
    post<SlackInteractionsPath> {
        val body = call.receive<ByteArray>()
        val headers = call.request
        val signed = signature.verify(body, headers.header(TIMESTAMP_HEADER), headers.header(SIGNATURE_HEADER))
        if (!signed) throw OstromException(ErrorKind.UNAUTHORIZED, "This interaction is not signed by the Slack app.")
        // Slack waits 3 seconds for a 200; anything but a button of a request is acknowledged and ignored.
        clickOf(body.decodeToString())?.let { clicks(it) }
        call.respond(HttpStatusCode.OK)
    }
    authenticated {
        get<SlackLinksPath> {
            val actor = adminOf(call)
            call.respond(SlackLinksPage(links.list(actor.orgId).map { it.toDto() }))
        }
        put<SlackLinksPath.Person> { params ->
            val actor = adminOf(call)
            val body = call.receive<SlackLinkRequest>()
            call.respond(links.link(actor.orgId, params.personId, body.teamId to body.userId, actor.id).toDto())
        }
        delete<SlackLinksPath.Person> { params ->
            links.unlink(adminOf(call).orgId, params.personId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// Only a person's browser session of an owner or an admin of the organization changes who is reached on Slack.
private suspend fun SlackRoutesDependencies.adminOf(call: ApplicationCall): GateActor {
    val principal = call.requirePrincipal()
    principal.requireAnyScope(setOf(GateScopes.ALL_USER))
    val actor = actors.resolve(principal)
        ?: throw OstromException(ErrorKind.FORBIDDEN, "This credential belongs to no organization.")
    val member = call.requireOrgPrincipal(members, actor.orgId)
    if (member.role != OrgRole.OWNER && member.role != OrgRole.ADMIN) {
        throw OstromException(ErrorKind.FORBIDDEN, "Only an owner or an admin links members to Slack.")
    }
    return actor
}

/**
 * Reads the click of a request's button from an interaction body (`payload=<json>`, form-encoded).
 *
 * @param body The verified body.
 * @return The click, or null for any other interaction.
 */
internal fun clickOf(body: String): SlackClick? = payloadOf(body)?.let(::clickIn)

private fun payloadOf(body: String): Payload? {
    val raw = body.split('&').firstOrNull { it.startsWith(PAYLOAD) }?.removePrefix(PAYLOAD)
    return try {
        raw?.let { json.decodeFromString<Payload>(it.decodeURLQueryComponent(plusIsSpace = true)) }
    } catch (_: IllegalArgumentException) {
        // A body Slack would never send (kotlinx's SerializationException is one too): acknowledged, ignored.
        null
    } catch (_: URLDecodeException) {
        null
    }
}

// A button of a request carries `<request id>:<shown digest>`; any other action is no click of ours.
private fun clickIn(payload: Payload): SlackClick? {
    val bound = payload.actions.firstOrNull { it.actionId == SlackMessages.YES || it.actionId == SlackMessages.NO }
        ?.let { action ->
            action.value.split(':', limit = 2).takeIf { it.size == 2 }?.let { (id, digest) ->
                Uuid.parseOrNull(id)?.let { Triple(action.actionId == SlackMessages.YES, it, digest) }
            }
        }
    val channel = payload.container.channelId
    if (payload.type != BLOCK_ACTIONS || bound == null || channel.isEmpty()) return null
    return SlackClick(
        teamId = payload.team.id,
        userId = payload.user.id,
        yes = bound.first,
        requestId = bound.second,
        shownDigest = bound.third,
        channel = channel,
        ts = payload.container.messageTs,
    )
}

private fun SlackLink.toDto() = SlackLinkDto(personId, teamId, userId, linkedBy, linkedAt.toString())

private val json = Json { ignoreUnknownKeys = true }

private const val TIMESTAMP_HEADER = "X-Slack-Request-Timestamp"
private const val SIGNATURE_HEADER = "X-Slack-Signature"
private const val PAYLOAD = "payload="
private const val BLOCK_ACTIONS = "block_actions"

@Serializable
private data class Payload(
    val type: String = "",
    val team: Id = Id(""),
    val user: Id = Id(""),
    val actions: List<Action> = emptyList(),
    val container: Container = Container(),
)

@Serializable
private data class Id(val id: String)

@Serializable
private data class Action(@SerialName("action_id") val actionId: String = "", val value: String = "")

@Serializable
private data class Container(
    @SerialName("channel_id") val channelId: String = "",
    @SerialName("message_ts") val messageTs: String = "",
)
