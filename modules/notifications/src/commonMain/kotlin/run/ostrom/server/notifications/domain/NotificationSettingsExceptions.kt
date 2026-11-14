// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * A channel the server does not offer, or an unknown kind: 400 `invalid_notification_setting`.
 *
 * @param message What to fix.
 */
class InvalidNotificationSettingException(message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_notification_setting")

/** A credential that is not a person's interactive login: 403. A service key has no person to notify. */
class NotificationSettingsForbiddenException : OstromException(
    ErrorKind.FORBIDDEN,
    "Notification settings are a person's own: sign in with a browser, the app or `ostrom login`.",
)
