// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `GET` and `POST /api/v1/admin/instance-admins` (`listInstanceAdmins`, `addInstanceAdmin`). */
@Serializable
@Resource("/api/v1/admin/instance-admins")
class InstanceAdminsApi {
    /**
     * `DELETE /api/v1/admin/instance-admins/{userId}` (`removeInstanceAdmin`).
     *
     * @property parent The administrators.
     * @property userId The administrator removed.
     */
    @Serializable
    @Resource("{userId}")
    class One(val parent: InstanceAdminsApi = InstanceAdminsApi(), val userId: String)
}

/** `GET /api/v1/admin/settings` (`listInstanceSettings`). */
@Serializable
@Resource("/api/v1/admin/settings")
class InstanceSettingsApi {
    /**
     * `GET` and `PUT /api/v1/admin/settings/{section}` (`getInstanceSettings`, `saveInstanceSettings`).
     *
     * @property parent The sections.
     * @property section The section.
     */
    @Serializable
    @Resource("{section}")
    class Section(val parent: InstanceSettingsApi = InstanceSettingsApi(), val section: String)
}

/**
 * An administrator of the instance (`InstanceAdmin`).
 *
 * @property userId The account.
 * @property login Its handle.
 * @property email Its verified primary e-mail.
 * @property source `setup`, `dashboard` or `environment`.
 * @property addedAt When.
 * @property addedBy Who added them.
 */
@Serializable
data class InstanceAdminResponse(
    val userId: String,
    val login: String? = null,
    val email: String? = null,
    val source: String,
    val addedAt: String,
    val addedBy: String? = null,
)

/**
 * The administrators (`InstanceAdminList`).
 *
 * @property admins They, oldest first.
 */
@Serializable
data class InstanceAdminListResponse(val admins: List<InstanceAdminResponse>)

/**
 * `AddInstanceAdminRequest`.
 *
 * @property email A verified address of the person's account.
 */
@Serializable
data class AddInstanceAdminRequest(val email: String)

/**
 * `InstanceSecretState`.
 *
 * @property configured Whether the secret is set.
 */
@Serializable
data class InstanceSecretStateResponse(val configured: Boolean)

/**
 * `InstanceSettingField`.
 *
 * @property name The field.
 * @property secret Whether it is write-only.
 * @property required Whether the section does nothing without it.
 */
@Serializable
data class InstanceSettingFieldResponse(val name: String, val secret: Boolean, val required: Boolean)

/**
 * A section (`InstanceSettings`).
 *
 * @property section Its name.
 * @property version Its version, 0 before the first save.
 * @property values The plain fields set.
 * @property secrets Whether each secret is set.
 * @property fields Its fields.
 * @property updatedAt When it was saved last.
 * @property updatedBy Who saved it last.
 * @property importedFromEnvironment Whether these are the settings imported from the former variables.
 */
@Serializable
data class InstanceSettingsResponse(
    val section: String,
    val version: Int,
    val values: Map<String, String>,
    val secrets: Map<String, InstanceSecretStateResponse>,
    val fields: List<InstanceSettingFieldResponse>,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
    val importedFromEnvironment: Boolean,
)

/**
 * `InstanceSettingsList`.
 *
 * @property sections Every section, by name.
 */
@Serializable
data class InstanceSettingsListResponse(val sections: List<InstanceSettingsResponse>)

/**
 * `SaveInstanceSettingsRequest`.
 *
 * @property version The version replaced.
 * @property values Every plain field.
 * @property secrets The secrets changed: a value replaces, null removes, empty or absent keeps.
 */
@Serializable
data class SaveInstanceSettingsRequest(
    val version: Int,
    val values: Map<String, String> = emptyMap(),
    val secrets: Map<String, String?> = emptyMap(),
) {
    override fun toString(): String = "SaveInstanceSettingsRequest(version=$version, values=${values.keys}, " +
        "secrets=${secrets.keys})"
}
