// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin.domain

import run.ostrom.server.kernel.settings.InstanceAuditRecord
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.secrets.domain.SecretCipher
import kotlin.time.Clock

/**
 * A section as an administrator sees it: its fields, the plain values, and only whether each secret is set.
 *
 * @property section The section's declaration.
 * @property stored What is stored, or null before the first save.
 */
data class SettingsView(val section: SettingsSection, val stored: StoredSettings?) {
    /** The version a save replaces: 0 before the first. */
    val version: Int get() = stored?.version ?: 0

    /** Whether each secret field is set, by name. */
    val secrets: Map<String, Boolean>
        get() = section.fields.filter { it.secret }
            .associate { it.name to (stored?.sealed.orEmpty().containsKey(it.name)) }
}

/**
 * A save of a section.
 *
 * @property version The version replaced, 0 before the first save.
 * @property values Every plain field, by name; an absent or empty one unset.
 * @property secrets The secrets changed: a value replaces, null removes, an empty value keeps; an absent one keeps.
 */
data class SettingsChange(
    val version: Int,
    val values: Map<String, String>,
    val secrets: Map<String, String?>,
) {
    override fun toString(): String = "SettingsChange(version=$version, values=${values.keys}, secrets=${secrets.keys})"
}

/** The instance's settings sections, read and saved by an administrator. */
interface InstanceSettingsUseCase {
    /**
     * Every section.
     *
     * @param caller An administrator.
     *
     * @return They, by name.
     */
    suspend fun list(caller: AdminCaller): List<SettingsView>

    /**
     * One section.
     *
     * @param caller An administrator.
     * @param section Its name.
     *
     * @return It.
     *
     * @throws SettingsSectionNotFoundException When the server knows no such section.
     */
    suspend fun get(caller: AdminCaller, section: String): SettingsView

    /**
     * Saves a section.
     *
     * @param caller An administrator.
     * @param section Its name.
     * @param change The new settings.
     *
     * @return The section saved.
     *
     * @throws SettingsSectionNotFoundException When the server knows no such section.
     * @throws InvalidSettingsException When a field is unknown or a value refused.
     * @throws SettingsConflictException When another save came first.
     */
    suspend fun save(caller: AdminCaller, section: String, change: SettingsChange): SettingsView
}

/**
 * [InstanceSettingsUseCase]: secrets sealed with the secrets key, bound to their section and field (TeamCode's
 * `CredentialCipher`), never returned; every save in the instance's audit log with the fields it changed.
 *
 * @param sections The sections the modules declare.
 * @param repository The stored sections.
 * @param admins Who may.
 * @param cipher The secrets key.
 * @param reader The cache every replica reads, forgotten here at once on a save.
 * @param audit The instance's audit log.
 * @param clock The clock.
 */
@Suppress("LongParameterList") // The sections, their store and cache, who may, the key, the audit log, the clock.
class InstanceSettingsUseCaseImpl(
    sections: List<SettingsSection>,
    private val repository: InstanceSettingsRepository,
    private val admins: InstanceAdminsUseCase,
    private val cipher: SecretCipher,
    private val reader: InstanceSettingsReader,
    private val audit: InstanceAuditTrail,
    private val clock: Clock,
) : InstanceSettingsUseCase {
    private val sections = sections.sortedBy { it.name }.associateBy { it.name }

    init {
        // Two modules declaring one section would leave one's fields unsettable: refused at start instead.
        require(this.sections.size == sections.size) { "Two settings sections have one name." }
    }

    override suspend fun list(caller: AdminCaller): List<SettingsView> {
        admins.requireAdmin(caller)
        val stored = repository.all()
        return sections.values.map { SettingsView(it, stored[it.name]) }
    }

    override suspend fun get(caller: AdminCaller, section: String): SettingsView {
        admins.requireAdmin(caller)
        return SettingsView(declared(section), repository.get(section))
    }

    override suspend fun save(caller: AdminCaller, section: String, change: SettingsChange): SettingsView {
        admins.requireAdmin(caller)
        val declared = declared(section)
        problem(declared, change)?.let { throw InvalidSettingsException(it) }
        val stored = current(section, change.version)
        val values = change.values.filterValues { it.isNotEmpty() }
        val sealed = stored?.sealed.orEmpty().toMutableMap()
        change.secrets.forEach { (field, value) ->
            when {
                value == null -> sealed.remove(field)
                value.isNotEmpty() ->
                    sealed[field] = cipher.seal(value.encodeToByteArray(), settingContext(section, field))
            }
        }
        val next = StoredSettings(section, values, sealed, change.version + 1, clock.nowMillis(), caller.userId)
        val saved = if (stored == null) repository.insertIfAbsent(next) else repository.save(next)
        if (!saved) throw SettingsConflictException()
        reader.forget(section)
        val details = details(stored, next, change)
        audit.record(InstanceAuditRecord(caller.userId, "instance.settings.saved", "settings", section, details))
        return SettingsView(declared, next)
    }

    private fun declared(section: String): SettingsSection =
        sections[section] ?: throw SettingsSectionNotFoundException()

    // What a save replaces: the stored section, when it is still the version the administrator edited.
    private suspend fun current(section: String, version: Int): StoredSettings? =
        repository.get(section).also { if ((it?.version ?: 0) != version) throw SettingsConflictException() }

    // Why the change is refused, naming the field, never echoing its value; null when it is not.
    private suspend fun problem(section: SettingsSection, change: SettingsChange): String? {
        if (change.version !in 0..<Int.MAX_VALUE) return "version is a version this section had."
        return change.values.entries.firstNotNullOfOrNull { (name, value) -> plainProblem(section, name, value) }
            ?: change.secrets.entries.firstNotNullOfOrNull { (name, value) -> secretProblem(section, name, value) }
    }

    private suspend fun plainProblem(section: SettingsSection, name: String, value: String): String? {
        val field = section.field(name)?.takeUnless { it.secret }
            ?: return "$name is not a plain field of ${section.name}."
        return when {
            value.length > MAX_VALUE -> "$name is $MAX_VALUE characters at most."
            value.isEmpty() -> null
            else -> field.refusal(value)?.let { "$name: $it" }
        }
    }

    private suspend fun secretProblem(section: SettingsSection, name: String, value: String?): String? {
        val field = section.field(name)?.takeIf { it.secret }
            ?: return "$name is not a secret of ${section.name}."
        return when {
            value.isNullOrEmpty() -> null
            value.length > MAX_SECRET -> "$name is $MAX_SECRET characters at most."
            else -> field.refusal(value)?.let { "$name: $it" }
        }
    }

    // The names of the fields changed, never a value: a secret's value never reaches the log.
    private fun details(before: StoredSettings?, after: StoredSettings, change: SettingsChange): Map<String, String> {
        val previous = before?.values.orEmpty()
        val changed = (previous.keys + after.values.keys).filter { previous[it] != after.values[it] }.sorted()
        val set = change.secrets.filterValues { !it.isNullOrEmpty() }.keys.sorted()
        val removed = change.secrets.filterValues { it == null }.keys.sorted()
        return buildMap {
            put("version", after.version.toString())
            if (changed.isNotEmpty()) put("changed", changed.joinToString(",").take(MAX_DETAIL))
            if (set.isNotEmpty()) put("secretsSet", set.joinToString(",").take(MAX_DETAIL))
            if (removed.isNotEmpty()) put("secretsRemoved", removed.joinToString(",").take(MAX_DETAIL))
        }
    }

    private companion object {
        const val MAX_VALUE = 4096
        const val MAX_SECRET = 16384
        const val MAX_DETAIL = 200
    }
}
