// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.usecases

import run.ostrom.server.compiler.domain.models.CompilerSettings
import run.ostrom.server.compiler.domain.models.CompilerSettingsChange
import run.ostrom.server.compiler.domain.models.StoredCompilerSettings
import run.ostrom.server.compiler.domain.repositories.CompilerSettingsRepository
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * How an organization compiles its packages, set by its owners and admins in the console (TeamCode's team settings):
 * whether each compilation pins itself (`auto`) or a person pins each version (`manual`), who may publish code in a
 * script, and who approves a gate that names no approver. Read at each compilation.
 */
interface CompilerSettingsUseCase {
    /**
     * Reads the settings.
     *
     * @param orgId The organization.
     * @return What is stored, or null for the defaults.
     */
    suspend fun read(orgId: Uuid): StoredCompilerSettings?

    /**
     * Replaces the settings, audited as `compiler.settings.changed`.
     *
     * @param orgId The organization.
     * @param login The caller's login.
     * @param role The caller's role.
     * @param principal The caller's credential, named in the audit log.
     * @param change The new settings and the version they replace.
     * @return What is stored now.
     * @throws OstromException A 403 for anyone but an owner or an admin, a 400 `invalid_compiler_settings` naming the
     *   field, a 409 `compiler_settings_conflict` when another save came first.
     */
    suspend fun save(
        orgId: Uuid,
        login: String,
        role: OrgRole?,
        principal: AuthenticatedPrincipal,
        change: CompilerSettingsChange,
    ): StoredCompilerSettings
}

/**
 * [CompilerSettingsUseCase] on the settings' storage.
 *
 * @param repository The stored settings.
 * @param audit The organization's audit log.
 * @param clock The time.
 */
class CompilerSettingsUseCaseImpl(
    private val repository: CompilerSettingsRepository,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
) : CompilerSettingsUseCase {
    override suspend fun read(orgId: Uuid): StoredCompilerSettings? = repository.read(orgId)

    override suspend fun save(
        orgId: Uuid,
        login: String,
        role: OrgRole?,
        principal: AuthenticatedPrincipal,
        change: CompilerSettingsChange,
    ): StoredCompilerSettings {
        if (role !in MANAGERS) throw OstromException(ErrorKind.FORBIDDEN, MANAGERS_ONLY)
        val settings = checked(change)
        val stored = repository.save(orgId, settings, login, clock.now(), change.expected)
            ?: throw OstromException(ErrorKind.CONFLICT, CONFLICT, type = "compiler_settings_conflict")
        val facts = auditDetails(
            "pin" to change.pin,
            "code_publishers" to settings.codePublishers?.size,
            "prod_approver" to settings.prodApprover,
        )
        audit.record(OrgAuditRecord(orgId, principal, CHANGED, "compiler_settings", "$orgId", facts))
        return stored
    }

    private fun checked(change: CompilerSettingsChange): CompilerSettings {
        invalid(change.pin !in PINS, "pin is auto or manual.")
        val publishers = change.codePublishers?.map { it.trim().lowercase() }?.distinct()
        invalid(publishers.orEmpty().size > MAX_PUBLISHERS, "At most $MAX_PUBLISHERS code publishers.")
        invalid(publishers.orEmpty().any { !login(it) }, "A code publisher is a login of 1 to $MAX_LOGIN characters.")
        val approver = change.prodApprover?.trim()
        invalid(approver != null && !login(approver), "prodApprover is a login of 1 to $MAX_LOGIN characters.")
        return CompilerSettings(change.pin == MANUAL, publishers, approver)
    }

    private fun login(value: String) = value.length in 1..MAX_LOGIN

    private fun invalid(refused: Boolean, reason: String) {
        if (refused) throw OstromException(ErrorKind.INVALID_REQUEST, reason, type = "invalid_compiler_settings")
    }

    /** The settings' vocabulary and bounds. */
    companion object {
        /** Each compilation pins itself, the default. */
        const val AUTO: String = "auto"

        /** A person pins each version. */
        const val MANUAL: String = "manual"

        /** The audit event of a save. */
        const val CHANGED: String = "compiler.settings.changed"

        /** The most code publishers listed. */
        const val MAX_PUBLISHERS: Int = 100

        /** The longest login. */
        const val MAX_LOGIN: Int = 100

        private val PINS = setOf(AUTO, MANUAL)
        private val MANAGERS = setOf(OrgRole.OWNER, OrgRole.ADMIN)
        private const val MANAGERS_ONLY = "Only an owner or an admin changes how the organization compiles."
        private const val CONFLICT = "Another save of the compiler settings came first: read them again."
    }
}
