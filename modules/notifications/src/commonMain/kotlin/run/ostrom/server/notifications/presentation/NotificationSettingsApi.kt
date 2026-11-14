// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `/api/v1/notifications/settings` (contract `getNotificationSettings`, `setNotificationSetting`). */
@Serializable
@Resource("/api/v1/notifications/settings")
class NotificationSettingsApi
