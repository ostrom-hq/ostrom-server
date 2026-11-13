// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** A cursor that is not a sequence, is negative, or is ahead of the organization's events (another server's): 400. */
class InvalidCursorException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "Use a non-negative event sequence of this organization, or reconnect without Last-Event-ID.",
    "invalid_cursor",
)

/** A `types` filter naming a type this server does not know, or too many: 400. */
class InvalidEventTypeException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "Name at most ${EventRules.MAX_TYPES} known event types, separated by commas.",
    "invalid_event_type",
)

/** The credential may not read the feed (`cloud:read`, `cloud:write` or `cloud:admin`): 403. */
class EventsScopeException : OstromException(ErrorKind.FORBIDDEN, "This credential does not allow cloud:read.")
