// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * A malformed name, label, path, artifact or body. [type] is the contract's key (`invalid_skill_name`,
 * `invalid_hook_definition`…).
 *
 * @param type The key.
 * @param message What to fix.
 */
class InvalidCatalogRequestException(type: String, message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, type)

/**
 * An artifact holds more content than its catalog accepts.
 *
 * @param type `skill_artifact_too_large` or `hook_artifact_too_large`.
 * @param max The bound, in bytes.
 */
class ArtifactTooLargeException(type: String, max: Long) :
    OstromException(ErrorKind.PAYLOAD_TOO_LARGE, "An artifact is limited to $max bytes of file content.", type)

/**
 * The state of the catalog does not allow the change. [type] is the contract's key (`skill_version_conflict`…).
 *
 * @param type The key.
 * @param message What to do instead.
 */
class CatalogConflictException(type: String, message: String) : OstromException(ErrorKind.CONFLICT, message, type)

/** The skill, its version or the member does not exist, or is not the caller's: the cases are never told apart. */
class CatalogNotFoundException(message: String = "Skill not found.") : OstromException(ErrorKind.NOT_FOUND, message)

/** The caller is not allowed to do this. */
class CatalogForbiddenException(message: String) : OstromException(ErrorKind.FORBIDDEN, message)

/**
 * A service key lacks the scope the request needs (403 `credential_scope`).
 *
 * @param scope The missing scope.
 */
class CredentialScopeException(scope: String) :
    OstromException(ErrorKind.FORBIDDEN, "This key lacks the $scope scope.", "credential_scope")

/** Installation reports need the CLI's token: it names the workstation (409 `device_required`). */
class DeviceRequiredException : OstromException(
    ErrorKind.CONFLICT,
    "Sign in with `ostrom login` to report installations from a workstation.",
    "device_required",
)

/**
 * The stored files no longer match their published digest: never served (500, the key `skill_integrity` or
 * `hook_integrity`).
 *
 * @param type The key.
 */
class ArtifactIntegrityException(type: String) :
    OstromException(ErrorKind.INTERNAL, "The stored artifact does not match its published digest.", type)

/**
 * Refuses a malformed request.
 *
 * @param condition What a valid request satisfies.
 * @param type The key of the refusal.
 * @param message What to fix, built only when the request is refused.
 *
 * @throws InvalidCatalogRequestException When [condition] is false.
 */
internal inline fun ensureValid(condition: Boolean, type: String, message: () -> String) {
    if (!condition) throw InvalidCatalogRequestException(type, message())
}
