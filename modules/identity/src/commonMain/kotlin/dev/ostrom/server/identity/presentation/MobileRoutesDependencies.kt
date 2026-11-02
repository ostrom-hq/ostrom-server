// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.ExchangeMobileCodeUseCase
import dev.ostrom.server.identity.domain.GetNotificationPreferencesUseCase
import dev.ostrom.server.identity.domain.SetNotificationPreferencesUseCase
import dev.ostrom.server.identity.domain.UnlockMobileAppUseCase

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
