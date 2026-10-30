// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException

/**
 * An event declared by a client is malformed, or of a kind only the server records.
 *
 * @param message What is wrong, safe for the client.
 */
class InvalidUsageEventException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/** A client sent more events in one request than [UsageRules.MAX_EVENTS_PER_REQUEST]. */
class TooManyUsageEventsException :
    OstromException(ErrorKind.PAYLOAD_TOO_LARGE, "At most ${UsageRules.MAX_EVENTS_PER_REQUEST} events per request.")

/**
 * A usage query is out of range (period, grouping, cursor).
 *
 * @param message What is wrong, safe for the client.
 */
class InvalidUsageQueryException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)
