// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills.presentation

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.outsideOrganization
import run.ostrom.server.skills.domain.ArtifactFile
import run.ostrom.server.skills.domain.CatalogCaller
import run.ostrom.server.skills.domain.CatalogForbiddenException
import run.ostrom.server.skills.domain.CredentialKind
import run.ostrom.server.skills.domain.Decision
import run.ostrom.server.skills.domain.FileMode
import run.ostrom.server.skills.domain.InstallationReport
import run.ostrom.server.skills.domain.InstallationStatus
import run.ostrom.server.skills.domain.InvalidCatalogRequestException
import kotlin.io.encoding.Base64

/**
 * The caller of a catalog route: a person in the console (a browser session), a person's token (the CLI's, a
 * personal token, the app's), or a service key acting as its creator. An agent's token reaches no catalog route: an
 * agent receives its skills from the workstation or the pod that launched it.
 *
 * @throws CatalogForbiddenException For an agent's or a device's token.
 */
suspend fun PrincipalOrgResolver.catalogCallerOf(principal: AuthenticatedPrincipal): CatalogCaller {
    val credential = when {
        principal.kind == PrincipalKind.SERVICE -> CredentialKind.SERVICE_KEY
        principal.kind != PrincipalKind.USER ->
            throw CatalogForbiddenException("An agent's token reaches no skill or hook catalog.")
        principal.channel == PrincipalChannel.BROWSER -> CredentialKind.CONSOLE
        else -> CredentialKind.PERSON_TOKEN
    }
    val member = resolve(principal, null) ?: throw principal.outsideOrganization()
    return CatalogCaller(
        orgId = member.orgId,
        memberId = member.memberId.toString(),
        administrator = member.role == OrgRole.OWNER || member.role == OrgRole.ADMIN,
        credential = credential,
        scopes = principal.scopes,
        workstation = principal.workstation,
        credentialId = principal.credentialId,
        principal = principal,
    )
}

/**
 * Decodes the files of a publication.
 *
 * @param kind `skill` or `hook`, for the error's type.
 *
 * @throws InvalidCatalogRequestException For an unknown mode, a symbolic link or malformed base64.
 */
fun List<SkillFileUploadDto>.toArtifactFiles(kind: String): List<ArtifactFile> = map { file ->
    val type = "invalid_${kind}_artifact"
    val mode = when (file.mode) {
        null, FileMode.FILE.wire -> FileMode.FILE
        FileMode.EXECUTABLE.wire -> FileMode.EXECUTABLE
        "symlink" -> throw InvalidCatalogRequestException(type, "Symbolic links are refused.")
        else -> throw InvalidCatalogRequestException(type, "A file's mode is file or executable.")
    }
    val content = try {
        Base64.Default.decode(file.contentBase64)
    } catch (_: IllegalArgumentException) {
        throw InvalidCatalogRequestException(type, "A file's content is standard padded base64.")
    }
    ArtifactFile(file.path, mode, content)
}

/**
 * Reads a decision.
 *
 * @throws InvalidCatalogRequestException For anything but `allow` or `deny`.
 */
fun SetAssignmentRequest.toDecision(kind: String): Decision = Decision.fromWire(decision)
    ?: throw InvalidCatalogRequestException("invalid_${kind}_assignment", "A decision is allow or deny.")

/**
 * Reads installation reports.
 *
 * @throws InvalidCatalogRequestException For an unknown status.
 */
fun ReportSkillInstallationsRequest.toReports(): List<InstallationReport> = installations.map { report ->
    val status = InstallationStatus.fromWire(report.status) ?: throw InvalidCatalogRequestException(
        "invalid_skill_installation",
        "A status is installed, failed, conflict or removed.",
    )
    InstallationReport(report.name, report.version, report.digest, status)
}
