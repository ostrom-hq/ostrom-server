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

/**
 * A credential that is not a person's interactive login, on the switches or the test push: 403. A service key has no
 * person to notify.
 */
class NotificationSettingsForbiddenException : OstromException(
    ErrorKind.FORBIDDEN,
    "Notifications are a person's own: sign in with a browser, the app or `ostrom login`.",
)

/** A test push while no provider is chosen: 409 `push_off`. */
class PushOffException : OstromException(
    ErrorKind.CONFLICT,
    "Choose a push provider in the instance's settings and save it before sending a test.",
    "push_off",
)

/** A test push for a member without a phone push can reach: 409 `push_no_device`. */
class NoPushPhoneException : OstromException(
    ErrorKind.CONFLICT,
    "Sign in with the Ostrom app on your phone and allow notifications, then send the test again.",
    "push_no_device",
)

/** More than one test push in ten seconds on this replica: 429 `push_test_rate_limited`. */
class PushTestRateLimitedException : OstromException(
    ErrorKind.RATE_LIMITED,
    "Wait a few seconds between two test pushes.",
    "push_test_rate_limited",
)
