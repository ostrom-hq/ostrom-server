// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** A field out of its bounds, an unknown scope, a bad cursor: 400 `invalid_memory`. */
class InvalidMemoryException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_memory")

/** The text looks like a credential: 422 `memory_secret`, nothing stored. */
class MemorySecretException : OstromException(
    ErrorKind.UNPROCESSABLE,
    "This looks like a credential. Memory is read back into agents and shared contexts, so never store secrets: " +
        "describe where to find them instead.",
    "memory_secret",
)

/** No such entry, or one the caller may not see: 404, the same answer either way. */
class MemoryNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Memory entry not found.")

/** The memory is full: 409 `memory_limit`. */
class MemoryLimitException(limit: Long) :
    OstromException(ErrorKind.CONFLICT, "This memory holds $limit entries. Forget an old one first.", "memory_limit")

/** The entry changed since it was read: 409 `memory_conflict`. */
class MemoryConflictException : OstromException(
    ErrorKind.CONFLICT,
    "This entry changed since you read it. Read it again and retry.",
    "memory_conflict",
)

/** A team entry the caller may not write or change: 403 `memory_team_forbidden`. */
class MemoryTeamForbiddenException(message: String) :
    OstromException(ErrorKind.FORBIDDEN, message, "memory_team_forbidden")

/** A credential without `memory:use`: 403 `credential_scope`. */
class MemoryScopeException : OstromException(
    ErrorKind.FORBIDDEN,
    "This credential does not grant memory:use.",
    "credential_scope",
)

/** An agent's team entry names no session whose person could answer it: 400 `memory_proposal_session`. */
class MemoryProposalSessionException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "A team entry is proposed from a session, whose owner decides: send the session id, or write a personal entry.",
    "memory_proposal_session",
)

/** Too many of a member's proposals wait for an answer: 409 `memory_proposals_limit`. */
class MemoryProposalsLimitException(limit: Long) : OstromException(
    ErrorKind.CONFLICT,
    "$limit proposals of yours are waiting for an answer. Answer or wait for them first.",
    "memory_proposals_limit",
)

/** The entry is no proposal waiting for an answer: 409 `memory_not_proposed`. */
class MemoryNotProposedException : OstromException(
    ErrorKind.CONFLICT,
    "This entry is not a proposal waiting for an answer.",
    "memory_not_proposed",
)

/** A member accepting a proposal, or rejecting another's: 403 `forbidden`. */
class MemoryProposalForbiddenException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only an administrator accepts a proposal; its author or an administrator rejects it.",
)

/** The session a proposal names is not the caller's, or does not exist: 404, the same answer either way. */
class MemorySessionNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Session not found.")
