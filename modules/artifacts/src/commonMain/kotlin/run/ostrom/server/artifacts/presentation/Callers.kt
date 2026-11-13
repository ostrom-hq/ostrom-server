// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.presentation

import run.ostrom.server.artifacts.domain.ArtifactCaller
import run.ostrom.server.artifacts.domain.InvalidArtifactRequestException
import run.ostrom.server.artifacts.domain.MissingScopeException
import run.ostrom.server.artifacts.domain.NotAMemberException
import run.ostrom.server.artifacts.domain.SignInRequiredException
import run.ostrom.server.artifacts.domain.UploadedFile
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import kotlin.io.encoding.Base64

/** Reading artifacts. */
const val ARTIFACTS_READ = "artifacts:read"

/** Publishing, sharing and deleting artifacts; includes [ARTIFACTS_READ]. */
const val ARTIFACTS_WRITE = "artifacts:write"

private const val ALL_USER_SCOPES = "user:*"

/**
 * Whether a credential carries a scope: itself, [ARTIFACTS_WRITE] for [ARTIFACTS_READ], or a member's full
 * interactive scope.
 */
fun AuthenticatedPrincipal.allowsArtifacts(scope: String): Boolean =
    scope in scopes || (scope == ARTIFACTS_READ && ARTIFACTS_WRITE in scopes) ||
        (kind == PrincipalKind.USER && ALL_USER_SCOPES in scopes)

/**
 * Changing an access level or deleting an artifact: [ARTIFACTS_WRITE] held by a person signed in. A service key
 * holding `artifacts:write` publishes, uploads and invites, but never does this (TeamCode `artifacts:write`).
 */
const val ARTIFACTS_MANAGE = "artifacts:manage"

/**
 * The member a request acts for.
 *
 * @param scope [ARTIFACTS_READ], [ARTIFACTS_WRITE], or [ARTIFACTS_MANAGE] (which needs [ARTIFACTS_WRITE] and refuses a
 *   service key).
 *
 * @throws MissingScopeException When the credential lacks the scope.
 * @throws SignInRequiredException When it is a service key asking [ARTIFACTS_MANAGE].
 * @throws NotAMemberException When it acts for no organization.
 */
suspend fun PrincipalOrgResolver.artifactCallerOf(principal: AuthenticatedPrincipal, scope: String): ArtifactCaller {
    val needed = if (scope == ARTIFACTS_MANAGE) ARTIFACTS_WRITE else scope
    if (!principal.allowsArtifacts(needed)) throw MissingScopeException(needed)
    if (scope == ARTIFACTS_MANAGE && principal.kind == PrincipalKind.SERVICE) throw SignInRequiredException()
    val member = resolve(principal, null) ?: throw NotAMemberException()
    // A service key reads and writes the artifacts of its creator, never as an administrator.
    val administers = member.role in ADMIN_ROLES && principal.kind != PrincipalKind.SERVICE
    return ArtifactCaller(member.orgId, member.memberId.toString(), administers)
}

private val ADMIN_ROLES = setOf(OrgRole.OWNER, OrgRole.ADMIN)

/**
 * Decodes the files of a request.
 *
 * @throws InvalidArtifactRequestException When an encoding is unknown or a base64 content is malformed.
 */
internal fun List<FileDto>.toUploads(): List<UploadedFile> = map { file ->
    val bytes = when (file.encoding ?: "utf8") {
        "utf8" -> file.content.encodeToByteArray()
        "base64" -> try {
            Base64.Default.decode(file.content)
        } catch (_: IllegalArgumentException) {
            throw InvalidArtifactRequestException("${file.path.take(MAX_ECHO)} is not valid base64.")
        }
        else -> throw InvalidArtifactRequestException("encoding is utf8 or base64.")
    }
    UploadedFile(file.path, bytes)
}

private const val MAX_ECHO = 200
