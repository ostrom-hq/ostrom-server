// SPDX-License-Identifier: Apache-2.0
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

/**
 * A token a program can present asks what only a person does: change an artifact's access, publish one as `link` or
 * delete one. An agent never makes an artifact public nor deletes one.
 */
class ArtifactPersonRequiredException(message: String = CHANGES_FOR_PEOPLE) :
    OstromException(ErrorKind.FORBIDDEN, message)

private const val CHANGES_FOR_PEOPLE =
    "Only a person changes who reads an artifact, makes it public or deletes it: do it in the console."

/**
 * A credential asked for more reading tickets in a minute than a person opens pages.
 *
 * @property retryAfterSeconds When to try again, for `Retry-After`.
 */
class ArtifactTicketsRateLimitedException(val retryAfterSeconds: Long) : OstromException(
    ErrorKind.RATE_LIMITED,
    "Too many artifact pages were opened in a minute. Try again in a moment.",
)

/** The caller acts for no organization: answered like an unknown organization. */
class NotAMemberException : OstromException(ErrorKind.NOT_FOUND, "Organization not found.")

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

/**
 * Another version was published between the read and the write: publishing again builds on it.
 *
 * @property current The live version when the refusal carries it (a commit's, as TeamCode's `{error, current}`), for
 *   the publisher to merge.
 */
class ArtifactConflictException(val current: ArtifactVersion? = null) : OstromException(
    ErrorKind.CONFLICT,
    "Another version of this artifact was published at the same time: publish again.",
    "artifact_conflict",
)

/** No isolated content origin is configured: pages are not served, so no ticket is minted. */
class ArtifactPagesUnavailableException : OstromException(
    ErrorKind.UNAVAILABLE,
    "This server does not serve artifact pages (no content origin is configured).",
)
