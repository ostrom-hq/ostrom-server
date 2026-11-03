// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

/** The longest name of a personal token or a service key. */
internal const val MAX_TOKEN_NAME = 100

/**
 * The name a person gives a personal token or a service key, trimmed. A governance confirmation shows it to the owner
 * or admin who confirms (audit 2, F1 bis), so it is the creator's text and never more: a control character (a line
 * break, a tab) or an invisible one (a bidi override, a zero-width space) is refused, as an empty or too long name.
 *
 * @param name The name as sent.
 *
 * @return The name, trimmed.
 *
 * @throws InvalidTokenNameException When it is empty, longer than [MAX_TOKEN_NAME] or holds such a character.
 */
internal fun tokenName(name: String): String {
    val label = name.trim()
    val hidden = label.any { it.isISOControl() || it.category == CharCategory.FORMAT }
    if (label.isEmpty() || label.length > MAX_TOKEN_NAME || hidden) throw InvalidTokenNameException()
    return label
}
