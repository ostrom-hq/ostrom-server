// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets.domain

/**
 * Removes secret values from text coming back from an upstream (a tool result, an error, a provider message), in
 * case the upstream echoes the credential it received. Values shorter than [MIN_LENGTH] are left alone: scrubbing
 * them would mangle ordinary output, and the secrets rules refuse them where they are injected.
 */
object SecretScrubber {
    /** The shortest value scrubbed. */
    const val MIN_LENGTH: Int = 8

    /** What replaces a value. */
    const val REDACTED: String = "[REDACTED]"

    /**
     * Scrubs a text.
     *
     * @param text The text.
     * @param secrets The values to remove; the longest first, so a value containing another is removed whole.
     *
     * @return The text without any of the values.
     */
    fun scrub(text: String, secrets: Collection<String>): String {
        var result = text
        for (secret in secrets.filter { it.length >= MIN_LENGTH }.sortedByDescending { it.length }) {
            if (secret in result) result = result.replace(secret, REDACTED)
        }
        return result
    }
}
