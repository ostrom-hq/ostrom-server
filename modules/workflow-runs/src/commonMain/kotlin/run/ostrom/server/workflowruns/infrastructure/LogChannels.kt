// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.infrastructure

import run.ostrom.core.api.models.NotificationStatus
import run.ostrom.server.workflowruns.domain.services.Delivery
import run.ostrom.server.workflowruns.domain.services.NotificationChannels
import kotlin.uuid.Uuid

/**
 * The channels until an integration delivers them: a `log:` channel is the notification's own record (the prototype's
 * journal), sent once stored; Slack and Discord are not connected here, so their notifications are recorded as not
 * sent, with why. The Slack and Discord integrations bind their own [NotificationChannels].
 */
class LogChannels : NotificationChannels {
    override suspend fun deliver(orgId: Uuid, channel: String, text: String): Delivery {
        val kind = channel.substringBefore(':')
        return if (kind == "log") {
            Delivery(NotificationStatus.SENT)
        } else {
            Delivery(NotificationStatus.NOT_SENT, "The $kind channel is not connected to this instance.")
        }
    }
}
