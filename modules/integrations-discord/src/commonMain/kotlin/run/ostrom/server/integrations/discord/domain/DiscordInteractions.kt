// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.domain

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import run.ostrom.server.gates.domain.models.Answer
import run.ostrom.server.gates.domain.models.AnswerChannel
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.usecases.AnswerGateUseCase
import run.ostrom.server.gates.domain.usecases.GetApprovalUseCase
import run.ostrom.server.kernel.errors.OstromException
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What a request to `/webhooks/discord` gets. */
sealed interface DiscordInteractionOutcome {
    /** Not signed by the instance's application, too old, or Discord is not configured: 401, nothing parsed. */
    data object Unauthorized : DiscordInteractionOutcome

    /** Signed, but not an interaction this server answers: 400. */
    data object BadRequest : DiscordInteractionOutcome

    /**
     * Signed and answered: 200 with this interaction response.
     *
     * @property response Discord's interaction response (`type` 1, 4, 6 or 7).
     */
    data class Respond(val response: JsonObject) : DiscordInteractionOutcome
}

/** Receives one of Discord's interactions (`/webhooks/discord`). */
fun interface ReceiveDiscordInteractionUseCase {
    /**
     * @param body The raw body, as received: what the signature covers.
     * @param signature The `X-Signature-Ed25519` header.
     * @param timestamp The `X-Signature-Timestamp` header.
     *
     * @return What to answer.
     */
    suspend operator fun invoke(body: ByteArray, signature: String?, timestamp: String?): DiscordInteractionOutcome
}

/**
 * [ReceiveDiscordInteractionUseCase]: Discord's signature (Ed25519 of the timestamp then the raw body, with the
 * application's public key) is checked, with a timestamp at most 5 minutes away, before anything is parsed. A PING
 * answers a PONG. A Reject answers no for the member who linked the Discord account that clicked it, when that member
 * is the request's approver, and the response itself updates the clicked message (UPDATE_MESSAGE); the other
 * approvers' messages are closed by the queue, with the answer's event. Anyone else's click gets a message only they
 * see, and changes nothing.
 *
 * @param apps The Discord application.
 * @param verifier Ed25519.
 * @param links The members' links.
 * @param people The members.
 * @param approvals Reads a request and what its gate shows.
 * @param answer Answers a request.
 * @param clock The time.
 */
@Suppress("LongParameterList") // One port per owner: settings, crypto, links, people, gates, clock.
class ReceiveDiscordInteractionUseCaseImpl(
    private val apps: DiscordApps,
    private val verifier: DiscordVerifier,
    private val links: DiscordLinksRepository,
    private val people: DiscordPeople,
    private val approvals: GetApprovalUseCase,
    private val answer: AnswerGateUseCase,
    private val clock: Clock,
) : ReceiveDiscordInteractionUseCase {
    @Suppress("ReturnCount") // Each refusal of the signature is the same 401, said as early as it is known.
    override suspend fun invoke(body: ByteArray, signature: String?, timestamp: String?): DiscordInteractionOutcome {
        // No signature or a stale timestamp is refused before the settings are read.
        val stamp = timestamp ?: return DiscordInteractionOutcome.Unauthorized
        val seconds = stamp.toLongOrNull()
        val fresh = seconds != null && abs(clock.now().epochSeconds - seconds) <= MAX_AGE_SECONDS
        if (signature == null || !fresh) return DiscordInteractionOutcome.Unauthorized
        val app = apps.current() ?: return DiscordInteractionOutcome.Unauthorized
        if (!verifier.verify(app.publicKey, stamp.encodeToByteArray() + body, signature)) {
            return DiscordInteractionOutcome.Unauthorized
        }
        val root = parse(body.decodeToString())
        return when (root?.number("type")) {
            PING -> respond(PONG)
            MESSAGE_COMPONENT -> component(root)
            else -> DiscordInteractionOutcome.BadRequest
        }
    }

    @Suppress("ReturnCount") // Another button, a click that changes nothing, or the Reject's update.
    private suspend fun component(root: JsonObject): DiscordInteractionOutcome {
        val customId = (root["data"] as? JsonObject)?.text("custom_id").orEmpty()
        // In a direct message the clicker is `user`; in a server, `member.user`.
        val userId = ((root["user"] as? JsonObject) ?: ((root["member"] as? JsonObject)?.get("user") as? JsonObject))
            ?.text("id")
        val content = (root["message"] as? JsonObject)?.text("content").orEmpty()
        if (!customId.startsWith(DiscordTexts.REJECT_PREFIX) || userId == null) return respond(DEFERRED_UPDATE)
        val login = try {
            reject(userId, customId.removePrefix(DiscordTexts.REJECT_PREFIX))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The database or the gates failed: Discord shows the clicker why nothing changed, never a failed call.
            return ephemeral("Ostrom could not record this answer; try again in a moment.")
        } ?: return ephemeral("This request is not yours to answer, or it is already decided.")
        val outcome = DiscordTexts.escape("Rejected by $login.")
        return DiscordInteractionOutcome.Respond(
            buildJsonObject {
                put("type", UPDATE_MESSAGE)
                putJsonObject("data") {
                    put("content", DiscordTexts.fit("$content\n$outcome"))
                    putJsonArray("components") { }
                    putJsonObject("allowed_mentions") { putJsonArray("parse") { } }
                }
            },
        )
    }

    // The approver's login once their no is recorded, or null when the click changes nothing.
    private suspend fun reject(discordUserId: String, value: String): String? {
        val orgId = Uuid.parseOrNull(value.substringBefore(':'))
        val approvalId = Uuid.parseOrNull(value.substringAfter(':', ""))
        val member = if (orgId == null || approvalId == null) {
            null
        } else {
            links.memberOf(orgId, discordUserId)?.let { people.member(orgId, it) }
        }
        if (orgId == null || approvalId == null || member == null) return null
        val actor = GateActor.person(member.login, orgId, member.role)
        return try {
            // The gates refuse anyone but the request's approver, and a decided gate.
            val view = approvals(actor, approvalId)
            answer(actor, view.request.id, Answer.NO, view.gate.shownDigest, AnswerChannel.DISCORD)
            member.login
        } catch (_: OstromException) {
            // Another approver, a decided gate, an unknown request: the click changes nothing.
            null
        }
    }

    private fun respond(type: Int) = DiscordInteractionOutcome.Respond(buildJsonObject { put("type", type) })

    private fun ephemeral(text: String) = DiscordInteractionOutcome.Respond(
        buildJsonObject {
            put("type", CHANNEL_MESSAGE)
            putJsonObject("data") {
                put("content", text)
                put("flags", EPHEMERAL)
            }
        },
    )

    private fun parse(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private companion object {
        const val PING = 1
        const val MESSAGE_COMPONENT = 3
        const val PONG = 1
        const val CHANNEL_MESSAGE = 4
        const val DEFERRED_UPDATE = 6
        const val UPDATE_MESSAGE = 7
        const val EPHEMERAL = 64
        const val MAX_AGE_SECONDS = 300L
    }
}
