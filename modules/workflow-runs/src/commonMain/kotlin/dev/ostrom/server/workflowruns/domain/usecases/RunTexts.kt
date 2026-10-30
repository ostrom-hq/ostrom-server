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
}
