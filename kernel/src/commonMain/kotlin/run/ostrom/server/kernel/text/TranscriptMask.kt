// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Masking and bounds for stored free text, ported from TeamCode's `CloudConversationRules`: the transcripts of a
 * session (see [TranscriptSanitizer]), and the secret scan of a file or of a memory entry ([detect]). The patterns are
 * bounded and anchored so a crafted text is scanned in linear time.
 */
// The shape rules are TeamCode's, kept as they are (and so as tested): their thresholds are the rules' own numbers.
@Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod", "ComplexCondition")
object TranscriptMask {
    /** Longest string kept in an event; longer strings are cut and marked. */
    const val MAX_EVENT_STRING_CHARS: Int = 16_000

    /** Largest encoded event; a larger one is replaced by a `truncated` marker. */
    const val MAX_EVENT_BYTES: Int = 60_000

    /** What a cut string ends with. */
    const val TRUNCATION_MARK: String = "…[truncated]"

    /** What replaces a credential. */
    const val MASK: String = "[REDACTED]"

    private const val MAX_WORD = 16

    /**
     * Credential shapes masked in every stored string: provider API keys, GitHub tokens, Ostrom credentials,
     * cloud keys, JWTs and private keys.
     */
    private val secrets = listOf(
        // Prefixed shapes start at a word boundary and contain a digit, so `task-management-…` stays readable.
        // Lookaheads are bounded, so a crafted string (`sk-sk-sk-…`) never makes a scan backtrack quadratically.
        Regex("(?<![A-Za-z0-9_-])sk-(?:ant-)?(?=[A-Za-z0-9_-]{0,256}[0-9])[A-Za-z0-9_-]{16,}"),
        Regex("(?<![A-Za-z0-9_])(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}"),
        Regex("github_pat_[A-Za-z0-9_]{20,}"),
        // Ostrom's credentials: a prefix of the identity module, then at least 32 random characters (a snake_case
        // name such as `ost_migration_2026` is no credential).
        Regex(
            "(?<![A-Za-z0-9_-])(?:osa|osb|osc|osg|osm|osp|oss|ost|opk)_" +
                "(?=[A-Za-z0-9_-]{0,256}[0-9])[A-Za-z0-9_-]{32,}",
        ),
        Regex("(?:AKIA|ASIA)[A-Z0-9]{16}"),
        Regex("xox[abprs]-[A-Za-z0-9-]{10,}"),
        // Anchored at a word start and possessive: `eyJeyJeyJ…` without a dot is scanned once, not once per `eyJ`.
        Regex("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}+\\.[A-Za-z0-9_-]{8,}+\\.[A-Za-z0-9_-]{8,}"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"),
    )

    /** The kind of each pattern of [secrets], in order, as [detect] names it. */
    private val secretKinds = listOf(
        "provider_api_key", "github_token", "github_token", "ostrom_credential", "aws_access_key", "slack_token", "jwt",
        "private_key",
    )

    private val authorization = Regex("(?i)\\b(bearer|basic|token)\\s+([A-Za-z0-9._~+/=-]{12,})")

    /** A name ending in a secret word (`GITHUB_TOKEN`, `"api_key"`, not `max_tokens`) given 8+ characters. */
    private val assignment = Regex(
        "(?i)\\b([A-Z0-9_]*(?:SECRET|TOKEN|PASSWORD|PASSWD|API_?KEY|PRIVATE_KEY|CREDENTIALS?))" +
            "(\"?\\s*[=:]\\s*)(\"[^\"]{8,}\"|'[^']{8,}'|[^\\s\"',;]{8,})",
    )

    /** The scheme is bounded, so a long run of scheme characters without `://` is scanned in linear time. */
    private val urlCredentials = Regex("(?i)([a-z][a-z0-9+.-]{0,31}://)[^\\s/@:]+:[^\\s/@]+@")

    /** On one line only: a page holds prose (`Basic cross-browser support`) and Markdown headings. */
    private val authorizationValue = Regex("(?i)\\b(bearer|basic|token)[ \\t]+([A-Za-z0-9._~+/=-]{12,})")

    /** A path: `/api/v1/items`, `./dist`, or one lowercase segment (`/artifacts`), not `/9j4AAQSkZJRg…` (base64). */
    private val path = Regex("\\.{0,2}/(?:[A-Za-z0-9._~-]+/)+[A-Za-z0-9._~-]*|\\.{0,2}/[a-z0-9._~-]*")
    private val namePart = Regex("[A-Z]+(?![a-z])|[A-Z]?[a-z]+|[0-9]+")

    /**
     * A plain word (`configuration`, `Authentication.`) after `token`, `basic` or `bearer` is prose. Anything else is
     * treated as a credential: a digit, a symbol, inner capitals, or letters too long for a word (a passphrase).
     */
    private fun looksLikeCredential(value: String): Boolean {
        val word = value.trimEnd('.')
        return word.length > MAX_WORD || word.any { !it.isLetter() } || word.drop(1).any { it.isUpperCase() }
    }

    /**
     * The kind of the first credential found in [text], or null: the shapes [mask] hides, plus authorization values
     * and URLs with credentials. Name-based assignments (`API_KEY = "…"`) are not reported: they are ordinary code in
     * a page and say nothing about the value. Used to refuse a file or a memory rather than to rewrite it.
     *
     * @param text The text.
     *
     * @return The kind (`provider_api_key`, `github_token`, `ostrom_credential`, `aws_access_key`, `slack_token`,
     *   `jwt`, `private_key`, `authorization_header` or `url_credentials`), or null.
     */
    fun detect(text: CharSequence): String? {
        secrets.forEachIndexed { index, pattern -> if (pattern.containsMatchIn(text)) return secretKinds[index] }
        val value = authorizationValue.findAll(text).any { looksLikeSecretValue(it.groupValues[1], it.groupValues[2]) }
        if (value) return "authorization_header"
        if (urlCredentials.containsMatchIn(text)) return "url_credentials"
        return null
    }

    /**
     * Stricter than [looksLikeCredential], since a hit refuses a whole file. A `Basic` value counts only when it is
     * base64 (padded or not) of a `user:password` pair. A `Bearer` or `Token` value counts when it is at least 12
     * characters with letters and digits, at least 20 with both cases, or at least 16 digits, and is neither a path
     * (`/api/v1/items`, `https://…`), nor an identifier made of lowercase words and numbers (`refresh_interval_v2`,
     * `2024-10-22-rotation-notes`), nor a camel- or Pascal-case name (`getAccessToken2`), which pages
     * legitimately contain.
     */
    private fun looksLikeSecretValue(scheme: String, value: String): Boolean {
        val word = value.trimEnd('.')
        if (scheme.equals("basic", ignoreCase = true)) return basicPair(word)
        if (path.matches(word)) return false
        if (word.length < 12 || identifier(word)) return false
        // A long run of digits alone is a numeric token; a shorter one is a number.
        if (word.none { it.isLetter() }) return word.length >= 16 && word.all { it.isDigit() }
        val plain = word.none { it.isUpperCase() } || word.none { it.isLowerCase() }
        if (word.none { it.isDigit() } && (word.length < 20 || plain)) return false
        val segments = word.split('.', '_', '-', '/', '~', '+', '=').filter { it.isNotEmpty() }
        val wordy = segments.size > 1 && segments.all { segment ->
            segment.all { it.isDigit() } ||
                (segment.all { it.isLowerCase() || it.isDigit() } && segment.count { it.isDigit() } <= 2)
        }
        return !wordy
    }

    /**
     * A camel- or Pascal-case name (`getAccessToken2`, `handleOAuth2Callback`, `RefreshTokenSchedulerService`): words
     * of three letters or more, acronyms and short numbers. A random token splits into short, mixed pieces instead.
     */
    private fun identifier(word: String): Boolean {
        val parts = namePart.findAll(word).map { it.value }.toList()
        if (parts.size < 2 || parts.joinToString("") != word) return false
        return parts.all { part ->
            when {
                part.all { it.isDigit() } -> part.length <= 2
                part.all { it.isUpperCase() } -> part.length <= 5
                else -> part.length >= 3
            }
        }
    }

    /** Base64, padded or not, of printable text holding a colon: what `Authorization: Basic` carries. */
    private fun basicPair(word: String): Boolean {
        val bare = word.trimEnd('=')
        val base64 = bare.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "+/" }
        if (bare.length % 4 == 1 || word.length - bare.length > 2 || !base64) return false
        val padded = bare + "=".repeat((4 - bare.length % 4) % 4)
        // The characters, the length and the padding are checked above: the decoder cannot refuse this value.
        val decoded = Base64.getDecoder().decode(padded)
        val text = try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(decoded)).toString()
        } catch (_: CharacterCodingException) {
            return false
        }
        return ':' in text && text.none { it.isISOControl() }
    }

    /**
     * Masks every credential shape of a text: provider API keys, GitHub, Ostrom, AWS and Slack tokens, JWTs, private
     * keys, bearer and basic authorization values, secret-named assignments and credentials in URLs.
     *
     * @param text The text.
     *
     * @return The text, each credential replaced by [MASK].
     */
    fun mask(text: CharSequence): String = mask(text) { it }

    /** [mask] with every pass reading the text through [view], so a test can count the characters read. */
    internal fun mask(text: CharSequence, view: (CharSequence) -> CharSequence): String {
        var masked = text.toString()
        for (pattern in secrets) masked = pattern.replace(view(masked), MASK)
        masked = authorization.replace(view(masked)) {
            if (looksLikeCredential(it.groupValues[2])) "${it.groupValues[1]} $MASK" else it.value
        }
        masked = assignment.replace(view(masked)) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        masked = urlCredentials.replace(view(masked)) { "${it.groupValues[1]}$MASK@" }
        return masked
    }

    /**
     * Masks, then cuts a string to a limit with a visible mark.
     *
     * @param text The text.
     * @param limit The most characters kept, mark included.
     *
     * @return The masked text, cut when it is longer than [limit].
     */
    fun bound(text: String, limit: Int = MAX_EVENT_STRING_CHARS): String {
        val masked = mask(text)
        return if (masked.length <= limit) masked else masked.take(limit - TRUNCATION_MARK.length) + TRUNCATION_MARK
    }
}
