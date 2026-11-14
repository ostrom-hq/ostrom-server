// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsField
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.runners.domain.AllowedOrgs
import run.ostrom.server.runners.domain.AllowedOrgsSource

/**
 * Which organizations may run cloud sessions on this server (audit S2-10: closed by default on a shared cluster), set
 * by an administrator of the instance in the console: `*`, or organization ids separated by commas. Not a secret, so
 * stored plain; imported once from the former `OSTROM_RUNNERS_ALLOWED_ORGS`.
 */
val CLOUD_ACCESS_SETTINGS: SettingsSection = SettingsSection(
    "cloud-access",
    listOf(
        SettingsField("allowedOrgs", legacyVariable = "OSTROM_RUNNERS_ALLOWED_ORGS") {
            if (AllowedOrgs.parse(it) == null) "* or organization ids separated by commas." else null
        },
    ),
)

/**
 * The organizations [CLOUD_ACCESS_SETTINGS] lists, read at each use (the instance's settings are kept 30 seconds per
 * replica): none while unset.
 *
 * @param settings The instance's settings.
 */
class SettingsAllowedOrgs(private val settings: InstanceSettings) : AllowedOrgsSource {
    override suspend fun allowedOrgs(): AllowedOrgs =
        settings.read(CLOUD_ACCESS_SETTINGS.name)["allowedOrgs"]?.let(AllowedOrgs::parse) ?: AllowedOrgs.NONE
}
