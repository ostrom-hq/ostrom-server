package dev.ostrom.server.artifacts.presentation

import dev.ostrom.server.artifacts.domain.ArtifactCaller
import dev.ostrom.server.artifacts.domain.InvalidArtifactRequestException
import dev.ostrom.server.artifacts.domain.MissingScopeException
import dev.ostrom.server.artifacts.domain.NotAMemberException
import dev.ostrom.server.artifacts.domain.UploadedFile
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
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
 * The member a request acts for.
 *
 * @throws MissingScopeException When the credential lacks [scope].
 * @throws NotAMemberException When it acts for no organization.
 */
suspend fun PrincipalOrgResolver.artifactCallerOf(principal: AuthenticatedPrincipal, scope: String): ArtifactCaller {
    if (!principal.allowsArtifacts(scope)) throw MissingScopeException(scope)
    val member = resolve(principal, null) ?: throw NotAMemberException()
    return ArtifactCaller(member.orgId, member.memberId.toString(), member.role in ADMIN_ROLES)
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
