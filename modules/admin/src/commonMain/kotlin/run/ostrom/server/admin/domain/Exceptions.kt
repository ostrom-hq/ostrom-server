// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** The caller is not a person who administers the instance. */
class InstanceAdminRequiredException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only an administrator of this instance, signed in, does this.",
    "instance_admin_required",
)

/** No account has verified this e-mail (the same answer for an unknown and an unverified address). */
class AccountNotFoundException : OstromException(
    ErrorKind.NOT_FOUND,
    "No account of this instance has verified this e-mail.",
    "account_not_found",
)

/** The person is not an administrator of the instance. */
class InstanceAdminNotFoundException : OstromException(
    ErrorKind.NOT_FOUND,
    "This person is not an administrator of the instance.",
    "instance_admin_not_found",
)

/** The last administrator stays. */
class LastInstanceAdminException : OstromException(
    ErrorKind.CONFLICT,
    "The instance keeps at least one administrator.",
    "last_instance_admin",
)

/** The server knows no settings section of this name. */
class SettingsSectionNotFoundException : OstromException(
    ErrorKind.NOT_FOUND,
    "This instance has no settings section of this name.",
    "settings_section_not_found",
)

/** Another save of the section came first. */
class SettingsConflictException : OstromException(
    ErrorKind.CONFLICT,
    "These settings changed since they were read: reload them.",
    "settings_conflict",
)

/**
 * A field is unknown or its value refused; the message names the field, never the value.
 *
 * @param message Why.
 */
class InvalidSettingsException(message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_settings")
