// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.presentation

import run.ostrom.server.artifacts.domain.ArtifactCaller
import run.ostrom.server.artifacts.domain.ArtifactOrigin
import run.ostrom.server.artifacts.domain.InvalidArtifactRequestException
import run.ostrom.server.artifacts.domain.MissingScopeException
import run.ostrom.server.artifacts.domain.NotAMemberException
import run.ostrom.server.artifacts.domain.UploadedFile
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.mayWrite
import kotlin.io.encoding.Base64

/** Reading artifacts. */
const val ARTIFACTS_READ = "artifacts:read"

/**
 * Publishing and sharing artifacts; includes [ARTIFACTS_READ]. Changing an artifact's access, making it public and
 * deleting it also need a person ([ArtifactCaller.person]): no token reaches them, whatever its scopes.
 */
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
 * The member a request acts for. Their role is not read: it gives no right over another member's artifact. Whether a
 * person presents the credential is [WriteAccess.PERSON]'s rule: a browser session, the unlocked app or the CLI's
 * sign-in, as TeamCode's interactive login.
 *
 * @throws MissingScopeException When the credential lacks [scope].
 * @throws NotAMemberException When it acts for no organization.
 */
suspend fun ArtifactOrgResolver.artifactCallerOf(principal: AuthenticatedPrincipal, scope: String): ArtifactCaller {
    if (!principal.allowsArtifacts(scope)) throw MissingScopeException(scope)
    // The origin of a publication is the agent token's launch, read here from the credential and nowhere else; an
    // agent token is read once, a dead launch's refused without a second lookup.
    // An agent token is its launch's member, null when it is no longer live; any other credential the kernel's.
    val agent = agentOf(principal, null)
    val member = (if (agent != null) agent.member else orgOf(principal)) ?: throw NotAMemberException()
    return ArtifactCaller(
        orgId = member.orgId,
        memberId = member.memberId.toString(),
        person = principal.mayWrite(WriteAccess.PERSON),
        credentialId = principal.credentialId,
        subjectId = principal.subjectId,
        principal = principal,
        administrator = member.role == OrgRole.OWNER || member.role == OrgRole.ADMIN,
        origin = agent?.let { ArtifactOrigin(it.launchId, it.cloudSessionId) },
    )
}

/**
 * Decodes the files of a request.
 *
 * @throws InvalidArtifactRequestException When a file has no content, an encoding is unknown or a base64 content is
 *   malformed.
 */
internal fun List<FileDto>.toUploads(): List<UploadedFile> = map { file ->
    val content = file.content
        ?: throw InvalidArtifactRequestException("${file.path.take(MAX_ECHO)} has no content (or name an upload).")
    UploadedFile(file.path, decode(content, file.encoding, file.path))
}

private fun decode(content: String, encoding: String?, path: String): ByteArray = when (encoding ?: "utf8") {
    "utf8" -> content.encodeToByteArray()
    "base64" -> try {
        Base64.Default.decode(content)
    } catch (_: IllegalArgumentException) {
        throw InvalidArtifactRequestException("${path.take(MAX_ECHO)} is not valid base64.")
    }
    else -> throw InvalidArtifactRequestException("encoding is utf8 or base64.")
}

private const val MAX_ECHO = 200

/** The member a credential other than an agent token acts for; an agent token is never resolved twice. */
private suspend fun ArtifactOrgResolver.orgOf(principal: AuthenticatedPrincipal) =
    if (principal.kind == PrincipalKind.AGENT) null else resolve(principal, null)
