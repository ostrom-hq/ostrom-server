// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.database

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlin.time.Instant

/** A purge date out of its range: in the future, or before 1970 (audit SERVEUR-3 B3). A 400. */
class PurgeDateRefusedException : OstromException(ErrorKind.INVALID_REQUEST, "A purge date is between 1970 and now.")

/**
 * The date a purge deletes before, checked before it reaches the database. A purge function crosses row-level
 * security for every organization: it also bounds the date itself, to its retention.
 */
object PurgeDates {
    /**
     * Checks a purge date.
     *
     * @param before Rows expired before it are deleted.
     * @param now The time.
     * @throws PurgeDateRefusedException When [before] is in the future or before 1970.
     */
    fun check(before: Instant, now: Instant) {
        if (before < Instant.fromEpochSeconds(0) || before > now) throw PurgeDateRefusedException()
    }
}
