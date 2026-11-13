// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills.domain

import run.ostrom.server.kernel.auth.MemberDirectory
import kotlin.time.Clock

/**
 * The files of an effective version, checked against its digest.
 *
 * @property skill The version.
 * @property entries The files' metadata, recomputed from their bytes.
 * @property files The files, sorted by path.
 */
class SkillDownload(val skill: EffectiveSkill, val entries: List<FileEntry>, val files: List<ArtifactFile>) {
    override fun toString(): String = "SkillDownload(skill=$skill, files=${entries.size})"
}

/** What a member receives, and what their workstations report of it. */
interface SkillDeliveryUseCase {
    /**
     * The skills [memberId] receives: active skills whose non-retired pinned version the member's decision, or the
     * audience, allows; a personal skill reaches its owner only. Evaluated on the shared state at each call.
     *
     * @return Sorted by name.
     *
     * @throws CatalogNotFoundException For a member of another organization.
     */
    suspend fun effective(caller: CatalogCaller, memberId: String): List<EffectiveSkill>

    /**
     * The files of [version] of [name], only while it is the caller's effective pinned version.
     *
     * @throws CatalogNotFoundException Otherwise.
     * @throws ArtifactIntegrityException When the stored files no longer match the digest.
     */
    suspend fun download(caller: CatalogCaller, name: String, version: String): SkillDownload

    /**
     * Stores what the caller's workstation installed. It never grants nor revokes access.
     *
     * @throws DeviceRequiredException For any credential but the CLI's.
     */
    suspend fun report(caller: CatalogCaller, reports: List<InstallationReport>)

    /** What [memberId]'s workstations reported: the member themself, or an owner or an admin in the console. */
    suspend fun installations(caller: CatalogCaller, memberId: String): List<Installation>
}

/** [SkillDeliveryUseCase] (TeamCode's `ResolveEffectiveSkillsUseCase`, `DownloadSkillUseCase` and reports). */
internal class SkillDeliveryUseCaseImpl(
    private val repository: SkillsRepository,
    private val members: MemberDirectory,
    private val sha256: Sha256,
    private val clock: Clock,
) : SkillDeliveryUseCase {
    override suspend fun effective(caller: CatalogCaller, memberId: String): List<EffectiveSkill> {
        CatalogAccess.requireReader(caller, memberId, SkillScopes.READ)
        if (memberId != caller.memberId && !members.isActiveMember(caller.orgId, memberId)) {
            throw CatalogNotFoundException("Member not found.")
        }
        return resolve(caller, memberId)
    }

    private suspend fun resolve(caller: CatalogCaller, memberId: String): List<EffectiveSkill> =
        repository.read(caller.orgId) {
            candidates(memberId, (SkillLimits.SKILLS + SkillLimits.PERSONAL).toInt())
        }.mapNotNull { candidate -> candidate.effectiveFor(memberId) }.sortedBy { it.name }

    override suspend fun download(caller: CatalogCaller, name: String, version: String): SkillDownload {
        CatalogAccess.requireReader(caller, caller.memberId, SkillScopes.READ)
        val skill = resolve(caller, caller.memberId).firstOrNull { it.name == name && it.version == version }
            ?: throw CatalogNotFoundException("This skill version is not delivered to you, or is not the pinned one.")
        val files = repository.read(caller.orgId) { files(skill.versionId) }
        val entries = ArtifactTree.recompute(files, sha256)
        if (ArtifactTree.digest(entries, sha256) != skill.digest) throw ArtifactIntegrityException("skill_integrity")
        return SkillDownload(skill, entries, files.sortedBy { it.path })
    }

    override suspend fun report(caller: CatalogCaller, reports: List<InstallationReport>) {
        val name = caller.workstation
        val id = caller.credentialId
        if (name == null || id == null) throw DeviceRequiredException()
        CatalogAccess.requireReader(caller, caller.memberId, SkillScopes.READ)
        ensureValid(reports.size <= SkillLimits.REPORTS, INVALID) { "Report at most ${SkillLimits.REPORTS} skills." }
        ensureValid(reports.map { it.name }.toSet().size == reports.size, INVALID) { "Report each skill once." }
        reports.forEach { report ->
            ArtifactTree.checkName(report.name, "skill")
            report.version?.let { ArtifactTree.checkVersion(it, "skill") }
            report.digest?.let(ArtifactTree::checkDigest)
            ensureValid(report.version != null || report.digest == null, INVALID) { "A digest names its version." }
        }
        if (reports.isEmpty()) return
        repository.write(caller.orgId) {
            val ids = reportable(reports.map { it.name }.toSet(), caller.memberId)
            val missing = reports.firstOrNull { it.name !in ids }
            if (missing != null) throw CatalogNotFoundException("Skill ${missing.name} is not in the catalog.")
            // A reported version is a published one, with its digest: a status never names unknown content.
            val published = digests(ids.values, reports.mapNotNull { it.version }.toSet())
            reports.forEach { report ->
                val label = report.version ?: return@forEach
                val digest = published[ids.getValue(report.name) to label]
                ensureValid(digest != null && (report.digest == null || report.digest == digest), INVALID) {
                    "Skill ${report.name} has no published version matching this report."
                }
            }
            saveInstallations(
                Workstation(id, name, caller.memberId),
                reports.map { ids.getValue(it.name) to it },
                clock.now(),
            )
        }
    }

    override suspend fun installations(caller: CatalogCaller, memberId: String): List<Installation> {
        if (memberId == caller.memberId) {
            PersonalAccess.requireOwner(caller, SkillScopes.READ)
        } else {
            CatalogAccess.requireConsoleAdministrator(caller)
            if (!members.isActiveMember(caller.orgId, memberId)) throw CatalogNotFoundException("Member not found.")
        }
        return repository.read(caller.orgId) { installations(memberId, SkillLimits.ROWS) }
    }

    private companion object {
        const val INVALID = "invalid_skill_installation"
    }
}

/**
 * The version [memberId] receives of this candidate, or null: an active skill, a non-retired pinned version, and the
 * access policy's yes. A personal skill reaches its owner without a decision row, and nobody else.
 */
internal fun Candidate.effectiveFor(memberId: String): EffectiveSkill? {
    val version = pinned
    val owner = skill.ownerId
    val allowed = when {
        owner != null -> owner == memberId
        else -> AccessPolicy.allowed(decision, skill.allMembers)
    }
    if (version == null || skill.retired || version.retired || !allowed) return null
    return EffectiveSkill(skill.name, version.id, version.version, version.digest, version.sizeBytes, version.fileCount)
}
