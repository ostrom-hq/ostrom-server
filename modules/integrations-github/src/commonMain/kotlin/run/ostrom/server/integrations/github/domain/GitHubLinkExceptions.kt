// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** The instance's App has no OAuth client: nobody can link (409 `github_app_not_configured`). */
class GitHubLinkAppNotConfiguredException : OstromException(
    ErrorKind.CONFLICT,
    "The instance's GitHub App cannot link accounts: an administrator sets its client id and secret first.",
    "github_app_not_configured",
)

/** Device Flow is off in the App's settings (409 `github_device_flow_disabled`). */
class GitHubDeviceFlowDisabledException : OstromException(
    ErrorKind.CONFLICT,
    "Device Flow is disabled for the instance's GitHub App: link from the console, or ask an administrator to " +
        "enable it in the App's settings.",
    "github_device_flow_disabled",
)

/** GitHub refused the App's client or callback, or issued a token that is not a GitHub App user token (409). */
class GitHubAppMisconfiguredException(message: String) :
    OstromException(ErrorKind.CONFLICT, message, "github_app_misconfigured")

/** A link attempt that does not exist, expired, was used, or is another member's (404 `github_link_not_found`). */
class GitHubLinkNotFoundException : OstromException(
    ErrorKind.NOT_FOUND,
    "This GitHub link attempt expired or was already used. Start again.",
    "github_link_not_found",
)

/** A web link finished in another browser session than the one that started it (403). */
class GitHubLinkBrowserMismatchException : OstromException(
    ErrorKind.FORBIDDEN,
    "Finish linking GitHub in the browser that started it.",
    "github_link_browser_mismatch",
)

/**
 * The member cancelled the authorization on GitHub, or GitHub needs something of them first (400).
 *
 * @param message What to do.
 */
class GitHubLinkDeniedException(message: String = "GitHub authorization was cancelled. Start again.") :
    OstromException(ErrorKind.INVALID_REQUEST, message, "github_link_denied")

/** That GitHub account is already linked to another member of the organization (409). */
class GitHubAccountInUseException : OstromException(
    ErrorKind.CONFLICT,
    "This GitHub account is already linked to another member of the organization.",
    "github_account_in_use",
)

/** GitHub did not answer, or answered something the server does not act on (503 `github_unreachable`). */
class GitHubUnreachableException :
    OstromException(ErrorKind.UNAVAILABLE, "GitHub could not be reached. Try again.", "github_unreachable")

/** Links are a person's: a service key, an agent or a device token never makes or reads one (403). */
class GitHubLinkPersonOnlyException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only a person signed in to Ostrom links a GitHub account.",
)

/** Another member's link is an owner's or an admin's to read, make or remove (403). */
class GitHubLinkAdminOnlyException : OstromException(
    ErrorKind.FORBIDDEN,
    "Only an owner or an admin manages another member's GitHub link.",
)

/** The member is not an active member of the organization (404, like an unknown one). */
class GitHubLinkMemberNotFoundException :
    OstromException(ErrorKind.NOT_FOUND, "Member not found.", "member_not_found")

/** Cloud sessions are off on this instance: no new link starts (404). */
class GitHubLinkCloudOffException :
    OstromException(ErrorKind.NOT_FOUND, "Cloud sessions are off on this server: no GitHub link to make.")
