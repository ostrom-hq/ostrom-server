// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsValues

/**
 * [InstanceSettings] held in memory, for a test without the admin module: the sections it sets, as an administrator
 * would save them in the console.
 *
 * @param sections The values of each section, by section; a section absent reads empty.
 */
class TestInstanceSettings(sections: Map<String, Map<String, String>> = emptyMap()) : InstanceSettings {
    // Replaced whole, never changed in place: a reader sees the old map or the new one.
    private var sections: Map<String, Map<String, String>> = sections

    /**
     * Saves a section, as an administrator would.
     *
     * @param section The section.
     * @param values Its values; an empty map unsets it.
     */
    fun save(section: String, values: Map<String, String>) {
        sections = sections + (section to values)
    }

    override suspend fun read(section: String): SettingsValues = SettingsValues(sections[section].orEmpty())

    /** The settings tests start from. */
    companion object {
        /**
         * An instance anyone may sign up on (`sign-up` set to `open`, as ostrom.run is): the default of [serverTest]
         * without the admin module.
         *
         * @return The settings.
         */
        fun openSignUp(): TestInstanceSettings = TestInstanceSettings(mapOf("sign-up" to mapOf("mode" to "open")))
    }
}
