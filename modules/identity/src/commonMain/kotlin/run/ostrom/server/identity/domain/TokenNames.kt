// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.domain

/** The longest name of a personal token or a service key. */
internal const val MAX_TOKEN_NAME = 100

/** What a personal token whose name holds nothing visible is shown as. */
internal const val UNNAMED_TOKEN = "unnamed token"

/** Joiners some scripts and emoji need to be spelled: invisible, but they hide nothing. */
private val JOINERS = setOf('‌', '‍')

/** Characters that break a line, hide or reorder text. */
private val HIDING = setOf(
    CharCategory.CONTROL,
    CharCategory.FORMAT,
    CharCategory.LINE_SEPARATOR,
    CharCategory.PARAGRAPH_SEPARATOR,
    CharCategory.PRIVATE_USE,
)

/**
 * Whether [char] breaks a line or hides text: a control or a formatting character (a bidi override, a zero-width
 * space), a line or paragraph separator, a private-use one, or a space other than the plain one; never a joiner.
 */
private fun hides(char: Char): Boolean = char !in JOINERS &&
    (char.category in HIDING || char.category == CharCategory.SPACE_SEPARATOR && char != ' ')

/**
 * The name a person gives a personal token or a service key, trimmed. A governance confirmation shows it to the owner
 * or admin who confirms (audit 2, F1 bis), so it is the creator's text and never more: a character that breaks a line
 * or hides text is refused, as an empty or too long name.
 *
 * @param name The name as sent.
 *
 * @return The name, trimmed.
 *
 * @throws InvalidTokenNameException When it is empty, longer than [MAX_TOKEN_NAME] or holds such a character.
 */
internal fun tokenName(name: String): String {
    val label = name.trim()
    if (label.isEmpty() || label.length > MAX_TOKEN_NAME || label.any(::hides)) throw InvalidTokenNameException()
    return label
}

/**
 * A stored name as a confirmation may show it: a token named before [tokenName] refused such characters has each
 * replaced by a space, the spaces collapsed; nothing visible left is [UNNAMED_TOKEN].
 *
 * @param name The name as stored.
 *
 * @return The name to show.
 */
internal fun shownTokenName(name: String): String {
    val words = name.map { if (hides(it)) ' ' else it }.joinToString("").split(' ').filter(String::isNotEmpty)
    return words.joinToString(" ").take(MAX_TOKEN_NAME).ifEmpty { UNNAMED_TOKEN }
}
