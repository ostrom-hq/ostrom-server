// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * An event declared by a client is malformed, or of a kind only the server records.
 *
 * @param message What is wrong, safe for the client.
 */
class InvalidUsageEventException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/** A client sent more events in one request than [UsageRules.MAX_EVENTS_PER_REQUEST]. */
class TooManyUsageEventsException :
    OstromException(ErrorKind.PAYLOAD_TOO_LARGE, "At most ${UsageRules.MAX_EVENTS_PER_REQUEST} events per request.")

/** A member recorded more than [UsageRules.MAX_EVENTS_PER_MINUTE] events this minute. */
class UsageRateLimitedException :
    OstromException(ErrorKind.RATE_LIMITED, "At most ${UsageRules.MAX_EVENTS_PER_MINUTE} events per minute.")

/**
 * A usage query is out of range (period, grouping, cursor).
 *
 * @param message What is wrong, safe for the client.
 */
class InvalidUsageQueryException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)
