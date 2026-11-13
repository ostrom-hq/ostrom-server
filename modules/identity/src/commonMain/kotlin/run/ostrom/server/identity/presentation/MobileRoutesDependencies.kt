// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.presentation

import run.ostrom.server.identity.domain.ExchangeMobileCodeUseCase
import run.ostrom.server.identity.domain.GetNotificationPreferencesUseCase
import run.ostrom.server.identity.domain.SetNotificationPreferencesUseCase
import run.ostrom.server.identity.domain.UnlockMobileAppUseCase

/**
 * What the phone app's routes need.
 *
 * @property exchangeCode Exchanges a mobile sign-in's code for the app's token.
 * @property unlock Records the phone's biometric unlock.
 * @property getNotifications Reads the caller's notification preferences.
 * @property setNotifications Sets them.
 */
data class MobileRoutesDependencies(
    val exchangeCode: ExchangeMobileCodeUseCase,
    val unlock: UnlockMobileAppUseCase,
    val getNotifications: GetNotificationPreferencesUseCase,
    val setNotifications: SetNotificationPreferencesUseCase,
)
