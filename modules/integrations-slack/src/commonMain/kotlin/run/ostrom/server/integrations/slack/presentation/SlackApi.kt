// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `/api/v1/slack/link`: the caller's own Slack link (`getSlackLink`, `linkSlack`, `unlinkSlack`). */
@Serializable
@Resource("/api/v1/slack/link")
class SlackLinkPath

/** `/webhooks/slack`: Slack's interactions, signed with the app's signing secret (outside the API prefix). */
@Serializable
@Resource("/webhooks/slack")
class SlackWebhookPath
