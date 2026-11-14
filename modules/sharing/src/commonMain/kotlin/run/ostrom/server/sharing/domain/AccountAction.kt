// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A recognized refusal of the subscription itself, rather than of the call, answered before any output: only the
 * account's owner can lift it, on the provider's site (TeamCode's `ProviderAccountAction`, #277). The account rests
 * [cooldown] through its shared cool-down, so no replica picks it meanwhile, and is tried again after it: nothing to
 * reactivate in Ostrom once the owner acted.
 *
 * @property code The gateway's code for a call no other account served.
 * @property cooldown How long the account rests.
 * @property explanation Ostrom's own words for the agent when no other account served the call: never the provider's
 *   text, nor which account it was.
 */
enum class AccountAction(val code: String, val cooldown: Duration, val explanation: String) {
    /** Claude asks the account's owner to accept its updated Consumer Terms and Privacy Policy. */
    ACCEPT_CLAUDE_TERMS(
        "provider_terms_required",
        60.seconds,
        "No shared account served this request. A Claude subscription requires acceptance of the updated Consumer " +
            "Terms and Privacy Policy. Ask its owner to sign in at https://claude.ai and accept them, then retry. " +
            "Ostrom temporarily skips that account and tries other eligible accounts first.",
    ),
}

/**
 * Reads Claude's account refusals in the body of a 400 (TeamCode's `ClaudeAccountRefusals`). Claude gives no
 * machine-readable subcode for them, so it fails closed: only its error envelope, `invalid_request_error` and the whole
 * message, ignoring case and whitespace. A keyword, a request error quoting the message, another shape or a body over
 * [MAX_BYTES] is no account refusal.
 */
object ClaudeAccountRefusals {
    /** The largest body read. */
    const val MAX_BYTES: Int = 4096

    /** How long the body is read at most. */
    val READ_TIMEOUT: Duration = 2.seconds

    private val whitespace = Regex("\\s+")

    // The full wording reported in https://github.com/anthropics/claude-code/issues/57569.
    private val termsMessage = normalize(
        "We've updated our Consumer Terms and Privacy Policy. You'll need to accept them in claude.ai with the " +
            "email in /status to continue.",
    )

    private fun normalize(message: String) = message.trim().lowercase().replace(whitespace, " ")

    /**
     * @param body The whole body of a 400.
     *
     * @return The action it asks of the account's owner, or null for any other refusal.
     */
    fun classify(body: ByteArray): AccountAction? {
        if (body.size > MAX_BYTES) return null
        val value = try {
            Json.parseToJsonElement(body.decodeToString()) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val error = value?.takeIf { it["type"] == JsonPrimitive("error") }?.get("error") as? JsonObject
        val message = error?.takeIf { it["type"] == JsonPrimitive("invalid_request_error") }?.get("message")
        return AccountAction.ACCEPT_CLAUDE_TERMS.takeIf {
            message is JsonPrimitive && message.isString && normalize(message.content) == termsMessage
        }
    }
}
