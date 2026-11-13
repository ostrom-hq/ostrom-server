// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.settings

/**
 * One field of a [SettingsSection].
 *
 * @property name The field's name, `camelCase`, as the console and the API show it.
 * @property secret True for a write-only secret: sealed with the instance's secrets key, never returned.
 * @property required True when the section does nothing until it is set.
 * @property legacyVariable The former environment variable that held it (`OSTROM_RESEND_API_KEY`): read once, into an
 *   empty database, then never again ([SettingsSection]).
 * @property check Refuses a value: returns why (never echoing the value), or null when it is acceptable.
 */
class SettingsField(
    val name: String,
    val secret: Boolean = false,
    val required: Boolean = false,
    val legacyVariable: String? = null,
    val check: (String) -> String? = { null },
)

/**
 * A section of the instance's settings, which a module declares by binding it in Koin (the admin module collects every
 * bound section). The settings serve every organization and are set by an administrator of the instance in the
 * console, stored in PostgreSQL with their secrets sealed: never in the environment or the Helm chart, except what the
 * server needs to start (TeamCode's rule, docs/MODULES.md "Settings in the console").
 *
 * When the stored section is empty and one of its [SettingsField.legacyVariable]s is set, the server imports the
 * section from the environment once at start and logs that the variable is deprecated; afterwards the variable is
 * ignored.
 *
 * @property name The section's key, `[a-z][a-z0-9-]*` (`mail`, `push`, `google`).
 * @property fields Its fields, in display order.
 */
class SettingsSection(val name: String, val fields: List<SettingsField>) {
    init {
        require(SECTION_NAME.matches(name)) { "A settings section is named [a-z][a-z0-9-]{0,39}: $name." }
        require(fields.map { it.name }.toSet().size == fields.size) { "The fields of $name have one name each." }
    }

    /**
     * The field named [name].
     *
     * @param name The name.
     *
     * @return The field, or null when the section has none of that name.
     */
    fun field(name: String): SettingsField? = fields.firstOrNull { it.name == name }
}

/**
 * The settings of a section as the module that uses them reads them: plain values and opened secrets together.
 * Never logged or serialized ([toString] names the fields only).
 *
 * @property values Every field set, by name; a field unset is absent.
 */
class SettingsValues(private val values: Map<String, String>) {
    /**
     * The value of a field.
     *
     * @param field The field's name.
     *
     * @return The value, or null when unset.
     */
    operator fun get(field: String): String? = values[field]

    /** Whether no field is set. */
    val isEmpty: Boolean get() = values.isEmpty()

    override fun toString(): String = "SettingsValues(${values.keys.sorted().joinToString()})"

    override fun equals(other: Any?): Boolean = other is SettingsValues && other.values == values

    override fun hashCode(): Int = values.hashCode()

    /** The empty settings. */
    companion object {
        /** No field set. */
        val EMPTY: SettingsValues = SettingsValues(emptyMap())
    }
}

/** The instance's settings, as the modules that use them read them. */
fun interface InstanceSettings {
    /**
     * Reads a section: what an administrator saved last, seen by every replica within 30 seconds.
     *
     * @param section The section's name.
     *
     * @return Its values, empty when nothing is stored (or the section is unknown).
     */
    suspend fun read(section: String): SettingsValues
}

/** No stored settings: the default of a server built without the admin module (a module's own tests). */
class EmptyInstanceSettings : InstanceSettings {
    override suspend fun read(section: String): SettingsValues = SettingsValues.EMPTY
}

private val SECTION_NAME = Regex("[a-z][a-z0-9-]{0,39}")
