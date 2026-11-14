// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package run.ostrom.server.ee.license.domain

import run.ostrom.server.kernel.settings.SettingsField
import run.ostrom.server.kernel.settings.SettingsSection

/**
 * The instance's license (Admin → Settings → license): its key, sealed, never returned. A key is verified when an
 * administrator saves it (its signature, its dates and the instance it is bound to) and again at start; a refused one
 * is never stored. `OSTROM_LICENSE_KEY` (or the file `OSTROM_LICENSE_KEY_FILE` names) is imported once, verified the
 * same way.
 */
object LicenseSettings {
    /** The section's name. */
    const val SECTION_NAME: String = "license"

    /** The license key, `ostrom-license.v1.<payload>.<signature>`. */
    const val LICENSE_KEY: String = "licenseKey"

    /**
     * The section the admin module serves and stores.
     *
     * @param licenses Verifies a key on this instance.
     *
     * @return The section.
     */
    fun section(licenses: VerifiedLicenseProvider): SettingsSection = SettingsSection(
        SECTION_NAME,
        listOf(
            SettingsField(
                LICENSE_KEY,
                secret = true,
                legacyVariable = "OSTROM_LICENSE_KEY",
                verify = { licenses.refusal(it) },
            ),
        ),
    )
}
