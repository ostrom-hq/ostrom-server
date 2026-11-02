// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.usecases

import dev.ostrom.server.workflowruns.domain.models.InvalidRunException

/** The bounds of what a hook reports. */
object RunTexts {
    /** The longest id, digest or short field. */
    const val ID: Int = 200

    /** The end of a check's output that is kept. */
    const val OUTPUT: Int = 4_000

    /** The longest command a notification quotes. */
    const val COMMAND: Int = 200

    /**
     * Refuses a short field over [ID] characters or with a control character.
     *
     * @param name The field.
     * @param value Its value, or null.
     *
     * @throws InvalidRunException When it is malformed.
     */
    fun bounded(name: String, value: String?) {
        if (value != null && (value.length > ID || value.any { it.isISOControl() })) {
            throw InvalidRunException("$name: at most $ID characters, without control characters.")
        }
    }

    /**
     * Masks what looks like a secret in a command or an output (a bearer token, a password in a URL, a `*_TOKEN=`
     * variable, a token flag, a provider's or Ostrom's key): it reaches a channel and every member's list (AGENTS.md
     * rule 9).
     *
     * @param text The text.
     *
     * @return The text, each secret replaced by `***`.
     */
    fun masked(text: String): String = SECRETS.fold(text) { masked, (pattern, replacement) ->
        pattern.replace(masked, replacement)
    }

    private val SECRETS: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\b(bearer|basic|token)\\s+[A-Za-z0-9._~+/=-]{8,}") to "$1 ***",
        Regex("://[^/\\s:@]+(:[^/\\s@]*)?@") to "://***@",
        Regex("(?i)\\b([A-Z0-9_]*(TOKEN|SECRET|PASSWORD|PASSWD|API_?KEY|ACCESS_?KEY)[A-Z0-9_]*)=\\S+") to "$1=***",
        Regex("(?i)(--?(token|password|passwd|secret|api-key|apikey)[= ])\\S+") to "$1***",
        Regex(
            "\\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{16,}" +
                "|xox[abprs]-[A-Za-z0-9-]{10,}|os[acp]_[0-9a-f]{16,}|AKIA[0-9A-Z]{16})\\b",
        ) to "***",
    )
}
