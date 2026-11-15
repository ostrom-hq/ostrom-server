// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.domain

import run.ostrom.core.api.models.SlackChannels
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** A credential that is not a person's interactive login: 403. A service key has no Slack account. */
class SlackLinkForbiddenException : OstromException(
    ErrorKind.FORBIDDEN,
    "A Slack link is a person's own: sign in with a browser, the app or `ostrom login`.",
)

/** No bot token is stored: 409 `slack_not_configured`. */
class SlackNotConfiguredException : OstromException(
    ErrorKind.CONFLICT,
    "Slack is not configured on this instance: an administrator saves its bot token and signing secret first.",
    SlackChannels.NOT_CONFIGURED,
)

/** The caller has no proven e-mail address to look up: 409 `slack_email_unproven`. */
class SlackEmailUnprovenException : OstromException(
    ErrorKind.CONFLICT,
    "Prove your e-mail address in Ostrom first: Slack is looked up by it.",
    SlackChannels.EMAIL_UNPROVEN,
)

/** Another member of the organization linked that Slack account: 409 `slack_user_taken`. */
class SlackUserTakenException : OstromException(
    ErrorKind.CONFLICT,
    "Another member of this organization linked that Slack account.",
    SlackChannels.USER_TAKEN,
)

/** No active Slack person has the caller's proven address: 404 `slack_user_not_found`. */
class SlackUserNotFoundException : OstromException(
    ErrorKind.NOT_FOUND,
    "No Slack account of the workspace has your proven e-mail address.",
    SlackChannels.USER_NOT_FOUND,
)

/** Slack did not answer: 503. */
class SlackUnavailableException : OstromException(ErrorKind.UNAVAILABLE, "Slack does not answer; try again later.")
