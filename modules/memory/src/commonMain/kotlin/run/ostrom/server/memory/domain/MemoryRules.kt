// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.domain

import kotlin.time.Instant

/** The bounds of the memory and its write-time checks, TeamCode's `MemoryRules`. */
object MemoryRules {
    const val TITLE_LENGTH: Int = 160
    const val SUMMARY_LENGTH: Int = 300
    const val CONTENT_LENGTH: Int = 16_000
    const val TAGS_LIMIT: Int = 8
    const val TAG_LENGTH: Int = 40
    const val REFS_LIMIT: Int = 10
    const val REF_LENGTH: Int = 300
    const val PERSONAL_LIMIT: Long = 2_000
    const val TEAM_LIMIT: Long = 5_000
    const val PAGE_LIMIT: Int = 50
    const val SEARCH_LIMIT: Int = 20
    const val SEARCH_TERMS: Int = 5
    private const val TERM_MIN = 2
    private const val TERM_MAX = 40

    private val tag = Regex("[a-z0-9][a-z0-9._-]{0,${TAG_LENGTH - 1}}")
    private val separators = Regex("[^\\p{L}\\p{N}._-]+")

    /**
     * Text that looks like a credential is refused at write time: a memory is read back into agents' contexts and, for
     * the organization's, by every member, so a secret stored once would be handed out for good. A filter of the common
     * shapes (provider and Ostrom token prefixes, private keys, bearer headers, `password=value`), not a guarantee.
     */
    private val secretShapes = listOf(
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        Regex("""\b(sk-ant-|sk-proj-|sk-)[A-Za-z0-9_-]{20,}"""),
        Regex("""\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{20,}"""),
        Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}"""),
        Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}"""),
        Regex("""\bAKIA[0-9A-Z]{16}\b"""),
        Regex("""\b(os[a-z]|opk)_[A-Za-z0-9_-]{16,}"""),
        Regex("""(?i)\bauthorization:\s*(bearer|basic)\s+\S{16,}"""),
        Regex(
            """(?i)\b(password|passwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token)""" +
                """\s*[:=]\s*["']?[^\s"']{12,}""",
        ),
    )

    /**
     * Checks an entry's fields.
     *
     * @param now The time, which an expiry must be after.
     * @throws InvalidMemoryException For a field out of its bounds.
     * @throws MemorySecretException When a text looks like a credential.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod") // An entry's six fields, each with its bound.
    fun validate(
        title: String,
        summary: String,
        content: String,
        tags: List<String>,
        refs: List<String>,
        expiresAt: Instant?,
        now: Instant,
    ) {
        valid(!title.isBlank() && title.length <= TITLE_LENGTH && title.none(Char::isISOControl)) {
            "Use a title of 1 to $TITLE_LENGTH characters on one line."
        }
        valid(!summary.isBlank() && summary.length <= SUMMARY_LENGTH && summary.none(Char::isISOControl)) {
            "Use a one-line summary of 1 to $SUMMARY_LENGTH characters."
        }
        valid(!content.isBlank() && content.length <= CONTENT_LENGTH && '\u0000' !in content) {
            "Use content of 1 to $CONTENT_LENGTH characters."
        }
        valid(tags.size <= TAGS_LIMIT && tags.toSet().size == tags.size && tags.all { tag.matches(it) }) {
            "Use at most $TAGS_LIMIT distinct tags of lowercase letters, digits, dots, dashes and underscores " +
                "(at most $TAG_LENGTH characters)."
        }
        val goodRefs = refs.all { !it.isBlank() && it.length <= REF_LENGTH && it.none(Char::isISOControl) }
        valid(refs.size <= REFS_LIMIT && goodRefs) {
            "Use at most $REFS_LIMIT references of 1 to $REF_LENGTH characters."
        }
        valid(expiresAt == null || expiresAt > now) { "An expiry must be in the future." }
        if ((listOf(title, summary, content) + refs).any { text -> secretShapes.any { it.containsMatchIn(text) } }) {
            throw MemorySecretException()
        }
    }

    /** Refuses a field out of its bounds with [message]. */
    private inline fun valid(ok: Boolean, message: () -> String) {
        if (!ok) throw InvalidMemoryException(message())
    }

    /**
     * Checks a tag of a filter.
     *
     * @throws InvalidMemoryException When it is not a tag.
     */
    fun validateTag(value: String) {
        if (!tag.matches(value)) {
            throw InvalidMemoryException("Use a tag of lowercase letters, digits, dots, dashes and underscores.")
        }
    }

    /**
     * The words of a search: lowercase, at most [SEARCH_TERMS] distinct words of 2 to 40 characters.
     *
     * @throws InvalidMemoryException When no word is left.
     */
    fun terms(query: String): List<String> =
        query.lowercase().split(separators).filter { it.length in TERM_MIN..TERM_MAX }.distinct().take(SEARCH_TERMS)
            .ifEmpty { throw InvalidMemoryException("Search for at least one word of two characters.") }
}
