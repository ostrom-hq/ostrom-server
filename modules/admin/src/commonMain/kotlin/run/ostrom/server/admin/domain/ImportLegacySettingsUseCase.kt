// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.admin.domain

import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.settings.InstanceAuditRecord
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import run.ostrom.server.kernel.settings.SettingsField
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.secrets.domain.SecretCipher
import kotlin.time.Clock

/**
 * Imports, once, the settings a manual deployment still sets in the former environment variables: a section nothing
 * was ever stored for takes the variables set ([run.ostrom.server.kernel.settings.SettingsField.legacyVariable]),
 * secrets sealed, as TeamCode seeds its cloud runtime from `TEAMCODE_CLOUD_*` (`initialCloudRuntime`). Afterwards the
 * console is the only place to change them, and a variable still set is ignored with a warning.
 */
interface ImportLegacySettingsUseCase {
    /**
     * Imports what is to import.
     *
     * @param environment Reads a variable; null when unset.
     */
    suspend operator fun invoke(environment: (String) -> String?)
}

/**
 * [ImportLegacySettingsUseCase].
 *
 * @param sections The sections the modules declare.
 * @param repository The stored sections.
 * @param cipher The secrets key.
 * @param audit The instance's audit log.
 * @param logger Where the deprecation is said (the variables' names, never their values).
 * @param clock The clock.
 */
class ImportLegacySettingsUseCaseImpl(
    private val sections: List<SettingsSection>,
    private val repository: InstanceSettingsRepository,
    private val cipher: SecretCipher,
    private val audit: InstanceAuditTrail,
    private val logger: Logger,
    private val clock: Clock,
) : ImportLegacySettingsUseCase {
    override suspend fun invoke(environment: (String) -> String?) {
        sections.forEach { section -> import(section, environment) }
    }

    private suspend fun import(section: SettingsSection, environment: (String) -> String?) {
        // An empty variable is an unset one, as an empty field of the console is.
        val set = section.fields.mapNotNull { field ->
            field.legacyVariable?.let { variable ->
                environment(variable)?.takeIf { it.isNotEmpty() }?.let { Triple(field, variable, it) }
            }
        }
        val (found, refused) = set.partition { (field, _, value) -> field.check(value) == null }
        if (refused.isNotEmpty()) {
            logger.warn(
                "Deprecated variables with a value the console refuses were not imported: set them in the console.",
                mapOf("section" to section.name, "variables" to refused.joinToString(",") { it.second }),
            )
        }
        val variables = found.joinToString(",") { it.second }
        when {
            found.isEmpty() -> Unit
            repository.get(section.name) != null -> logger.warn(
                "Ignored deprecated variables: these settings are stored and changed in the console only.",
                mapOf("section" to section.name, "variables" to variables),
            )
            else -> store(section, found, variables)
        }
    }

    private suspend fun store(
        section: SettingsSection,
        found: List<Triple<SettingsField, String, String>>,
        variables: String,
    ) {
        val values = found.filterNot { it.first.secret }.associate { it.first.name to it.third }
        val sealed = found.filter { it.first.secret }.associate { (field, _, value) ->
            field.name to cipher.seal(value.encodeToByteArray(), settingContext(section.name, field.name))
        }
        val stored = StoredSettings(section.name, values, sealed, 1, clock.nowMillis(), null)
        if (!repository.insertIfAbsent(stored)) return
        audit.record(
            InstanceAuditRecord(
                null,
                "instance.settings.imported",
                "settings",
                section.name,
                mapOf("fields" to found.joinToString(",") { it.first.name }.take(MAX_DETAIL)),
            ),
        )
        logger.warn(
            "Imported deprecated variables into the instance's settings, once: change them in the console " +
                "(Admin → Settings) and remove the variables.",
            mapOf("section" to section.name, "variables" to variables),
        )
    }

    private companion object {
        const val MAX_DETAIL = 200
    }
}
