// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.text

/**
 * Masks what looks like a secret in a command or an output an agent's workstation reports: a bearer token, a password
 * in a URL, a `*_TOKEN=` variable, a token flag, a provider's or Ostrom's key. Such a text reaches a channel, every
 * member's list or the organization's audit log (AGENTS.md rule 9), so the server masks it again whatever the
 * workstation already removed.
 */
object SecretMasks {
    /** What replaces a secret. */
    const val MASK: String = "***"

    /**
     * Masks a text.
     *
     * @param text The text.
     *
     * @return The text, each secret replaced by [MASK].
     */
    fun masked(text: String): String = SECRETS.fold(text) { masked, (pattern, replacement) ->
        pattern.replace(masked, replacement)
    }

    private val SECRETS: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\b(bearer|basic|token)\\s+[A-Za-z0-9._~+/=-]{8,}") to "$1 $MASK",
        Regex("://[^/\\s:@]+(:[^/\\s@]*)?@") to "://$MASK@",
        Regex("(?i)\\b([A-Z0-9_]*(TOKEN|SECRET|PASSWORD|PASSWD|API_?KEY|ACCESS_?KEY)[A-Z0-9_]*)=\\S+") to "$1=$MASK",
        Regex("(?i)(--?(token|password|passwd|secret|api-key|apikey)[= ])\\S+") to "$1$MASK",
        Regex(
            "\\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{16,}" +
                "|xox[abprs]-[A-Za-z0-9-]{10,}|os[acp]_[0-9a-f]{16,}|AKIA[0-9A-Z]{16})\\b",
        ) to MASK,
    )
}
