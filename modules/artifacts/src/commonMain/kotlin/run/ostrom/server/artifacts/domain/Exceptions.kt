// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** The artifact does not exist, or the caller may not read it: the two are never told apart. */
class ArtifactNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Artifact not found.")

/**
 * The request is malformed.
 *
 * @param message What to fix.
 */
class InvalidArtifactRequestException(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/** The caller reads the artifact but may not change it. */
class ArtifactNotWritableException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only the artifact's owner changes it.",
)

/**
 * A file holds what looks like a credential: the whole publication is refused, nothing is stored.
 *
 * @param path The file.
 */
class ArtifactSecretException(path: String) : OstromException(
    ErrorKind.INVALID_REQUEST,
    "$path looks like it holds a secret (a key or a token): remove it and publish again.",
)

/** The publication is larger than an artifact may be. */
class ArtifactTooLargeException(message: String) : OstromException(ErrorKind.PAYLOAD_TOO_LARGE, message)

/** The caller acts for no organization: answered like an unknown organization. */
class NotAMemberException : OstromException(ErrorKind.NOT_FOUND, "Organization not found.")

/** A service key asks what only a person signed in does (change an access level, delete an artifact): 403. */
class SignInRequiredException : OstromException(
    ErrorKind.FORBIDDEN,
    "A service key never changes an access level nor deletes an artifact: a person signed in does.",
)

/**
 * The caller's credential lacks the scope the action needs.
 *
 * @param scope The missing scope.
 */
class MissingScopeException(scope: String) : OstromException(
    ErrorKind.FORBIDDEN,
    "This credential lacks the $scope scope.",
)

/**
 * Refuses a malformed request.
 *
 * @param condition What a valid request satisfies.
 * @param message What to fix, built only when the request is refused.
 *
 * @throws InvalidArtifactRequestException When [condition] is false.
 */
internal inline fun ensureValid(condition: Boolean, message: () -> String) {
    if (!condition) throw InvalidArtifactRequestException(message())
}

/** Another version was published between the read and the write: publishing again builds on it. */
class ArtifactConflictException : OstromException(
    ErrorKind.CONFLICT,
    "Another version of this artifact was published at the same time: publish again.",
)

/** No isolated content origin is configured: pages are not served, so no ticket is minted. */
class ArtifactPagesUnavailableException : OstromException(
    ErrorKind.UNAVAILABLE,
    "This server does not serve artifact pages (no content origin is configured).",
)
