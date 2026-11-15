// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode

private const val TAG = "github"

private const val ADMIN_403 = "Not an owner or an admin of the organization, for another member's link and " +
    "for the list (`forbidden`)."

private const val LINK_403 = "A credential that is not a person's (a service key, an agent or a device " +
    "token), or a person's token without the `cloud:write` scope, `cloud:read` for a read (`forbidden`)."

private const val MEMBER_NF = "A member who is not in the organization, or an id that is not one " +
    "(`member_not_found`)."

private const val NO_APP = "The instance's GitHub App has no OAuth client yet " +
    "(`github_app_not_configured`)."

private const val ORG_NF = "No such organization for the caller (`not_found`)."

private const val UNREACHABLE = "GitHub did not answer, or answered something the server does not act on " +
    "(`github_unreachable`)."

/**
 * The members' links to their GitHub accounts: their own, by the device flow or the web flow, and the
 * organization's, for its owners and admins.
 */
internal fun ApiCatalog.gitHubLinkOperations() {
    ownLinkOperations()
    deviceLinkOperations()
    webLinkOperations()
    memberLinkOperations()
    memberLinkStartOperations()
}

/** The caller's own link to a GitHub account. */
private fun ApiCatalog.ownLinkOperations() {
    get("/api/v1/github/link") {
        operationId = "getGithubLink"
        tag(TAG)
        summary = "The caller's own GitHub link."
        description = "Which GitHub account the caller's cloud sessions use for Git and `gh`, never a " +
            "token. A person only: the console, the app or the CLI's login. It works while cloud sessions are " +
            "off, so a member can still see and remove a link."
        parameters {
            organizationHeader()
        }
        responses {
            ok<GitHubLinkDto>("The link; `state` is `not_linked` for a member who has none.")
            error(HttpStatusCode.Forbidden, LINK_403)
            notFound(ORG_NF)
        }
    }
    delete("/api/v1/github/link") {
        operationId = "unlinkGithub"
        tag(TAG)
        summary = "Remove the caller's own GitHub link."
        description = "Removes the link, then revokes its access token at GitHub, best effort and with the " +
            "App that issued it. 204 also when there is none. The member's authorization of the App stays on " +
            "GitHub, where only they manage it. Journaled `github.link.removed`."
        parameters {
            organizationHeader()
        }
        responses {
            empty("The link is removed, or there was none.")
            error(HttpStatusCode.Forbidden, LINK_403)
            notFound(ORG_NF)
        }
    }
}

/** Linking a GitHub account by the device flow, for the CLI and the app. */
private fun ApiCatalog.deviceLinkOperations() {
    post("/api/v1/github/link/device") {
        operationId = "startGithubDeviceLink"
        tag(TAG)
        summary = "Start linking a GitHub account by the device flow."
        description = "For the CLI and the app. The answer gives the code to enter on GitHub; the client " +
            "then polls `pollGithubDeviceLink`. The token exchange happens on the server: no client ever sees a " +
            "GitHub token. The attempt is single-use and ends with GitHub's code (15 minutes at most); a new " +
            "start replaces the member's previous attempt. The answer is `no-store`."
        parameters {
            organizationHeader()
        }
        responses {
            ok<GitHubDeviceLinkDto>("The attempt: the code to enter on GitHub and where.", HttpStatusCode.Created)
            error(HttpStatusCode.Forbidden, LINK_403)
            notFound("Cloud sessions are off on this server, or no such organization for the caller " +
                "(`not_found`).")
            conflict("The instance's App has no OAuth client yet (`github_app_not_configured`), or Device " +
                "Flow is off in the App's settings (`github_device_flow_disabled`): link from the console.")
            error(HttpStatusCode.ServiceUnavailable, UNREACHABLE)
        }
    }
    post("/api/v1/github/link/device/{id}") {
        operationId = "pollGithubDeviceLink"
        tag(TAG)
        summary = "Poll a device link."
        description = "One poll, by the member who started the attempt. The server asks GitHub at most once " +
            "per interval whatever the client's pace, and honors `slow_down`. A poll still completes a link " +
            "started before cloud sessions were turned off. Linking again replaces the member's link."
        parameters {
            organizationHeader()
            pathId("id", "The attempt, as `startGithubDeviceLink` answered it.")
        }
        responses {
            ok<GitHubDeviceLinkPollDto>("Where the link stands: `pending`, `linked` with the link, " +
                "`expired` or `denied`.")
            badRequest("GitHub needs the member to verify their primary email address first " +
                "(`github_link_denied`).")
            error(HttpStatusCode.Forbidden, LINK_403)
            notFound("No such attempt: unknown, used, malformed or another member's " +
                "(`github_link_not_found`), or the member left the organization (`member_not_found`).")
            conflict("That GitHub account is already linked to another member (`github_account_in_use`), " +
                "the App has no OAuth client yet (`github_app_not_configured`), or GitHub refused the App's " +
                "client (`github_app_misconfigured`).")
            error(HttpStatusCode.ServiceUnavailable, UNREACHABLE)
        }
    }
}

/** Linking a GitHub account by the web flow, from the console. */
private fun ApiCatalog.webLinkOperations() {
    get("/api/v1/github/link/start") {
        operationId = "startGithubWebLink"
        tag(TAG)
        summary = "Start linking a GitHub account from the console."
        description = "The web flow, for the caller's own link: a browser session only, started from the " +
            "console. Answers a redirect to GitHub's authorization with a single-use `state` and PKCE (S256), " +
            "whose hash is bound to the browser session that started it, for 10 minutes. GitHub then returns to " +
            "`githubLinkCallback`."
        parameters {
            organizationHeader()
        }
        responses {
            empty("A redirect to GitHub's authorization.", HttpStatusCode.Found)
            error(HttpStatusCode.Forbidden, "Not a browser session of a person, or a start from another " +
                "site (`forbidden`).")
            notFound("Cloud sessions are off on this server, or no such organization for the caller " +
                "(`not_found`).")
            conflict(NO_APP)
        }
    }
    get("/api/v1/github/link/callback") {
        operationId = "githubLinkCallback"
        tag(TAG)
        summary = "Finish linking a GitHub account (GitHub's return)."
        description = "GitHub's return from a web link, at the App's callback URL. The single-use state " +
            "identifies the attempt and its member, and must come back with the browser session that started " +
            "it: a link opened in another browser binds nothing. This route never answers with an error body: " +
            "the browser is always sent back to the console's `/github` page with `linked=1` or " +
            "`error=<error.type>`: `github_link_not_found`, `github_link_browser_mismatch`, " +
            "`github_link_denied`, `github_account_in_use`, `github_app_not_configured`, " +
            "`github_app_misconfigured`, `member_not_found` or `github_unreachable`."
        parameters {
            queryText("code", "GitHub's code.")
            queryText("state", "The state of the start.")
            queryText("error", "GitHub's error, when the member cancelled.")
        }
        responses {
            empty("A redirect to the console, with the outcome in its address.", HttpStatusCode.Found)
        }
    }
}

/** The links of the organization's members, for its owners and admins. */
private fun ApiCatalog.memberLinkOperations() {
    get("/api/v1/github/links") {
        operationId = "listGithubLinks"
        tag(TAG)
        summary = "Every member's GitHub link."
        description = "The organization's links, by GitHub login, at most 1000; never a token. An owner or " +
            "an admin, a person only."
        parameters {
            organizationHeader()
        }
        responses {
            ok<GitHubLinksPage>("The links of the organization.")
            error(HttpStatusCode.Forbidden, ADMIN_403)
            notFound(ORG_NF)
        }
    }
    get("/api/v1/github/links/{memberId}") {
        operationId = "getMemberGithubLink"
        tag(TAG)
        summary = "Another member's GitHub link."
        description = "An owner or an admin, a person only; the caller's own link is `getGithubLink`."
        parameters {
            organizationHeader()
            pathId("memberId", "The member's id.")
        }
        responses {
            ok<GitHubLinkDto>("The link; `state` is `not_linked` for a member who has none.")
            error(HttpStatusCode.Forbidden, ADMIN_403)
            notFound(MEMBER_NF)
        }
    }
    delete("/api/v1/github/links/{memberId}") {
        operationId = "unlinkMemberGithub"
        tag(TAG)
        summary = "Remove another member's GitHub link."
        description = "As `unlinkGithub` does for one's own: an owner or an admin, a person only. A member " +
            "who left the organization can still be unlinked. Journaled `github.link.removed` with the " +
            "administrator as actor and the member as subject."
        parameters {
            organizationHeader()
            pathId("memberId", "The member's id.")
        }
        responses {
            empty("The link is removed, or there was none.")
            error(HttpStatusCode.Forbidden, ADMIN_403)
            notFound(MEMBER_NF)
        }
    }
}

/** Linking a GitHub account to another member. */
private fun ApiCatalog.memberLinkStartOperations() {
    get("/api/v1/github/links/{memberId}/start") {
        operationId = "startMemberGithubWebLink"
        tag(TAG)
        summary = "Link a GitHub account to another member."
        description = "For an account nobody signs in to, such as a robot: an owner or an admin starts the " +
            "web flow of `startGithubWebLink` for the member, from the console in a browser session. The " +
            "attempt, and so the link, is bound to that member and the state to the administrator's browser " +
            "session. GitHub authorizes whichever account is signed in to github.com in that browser, so the " +
            "administrator signs in there as the account the member should use. Linking a member already linked " +
            "replaces the link; the administrator never sees a token. Journaled `github.link.started` before " +
            "`github.link.created`."
        parameters {
            organizationHeader()
            pathId("memberId", "The member's id.")
        }
        responses {
            empty("A redirect to GitHub's authorization.", HttpStatusCode.Found)
            error(HttpStatusCode.Forbidden, "Not an owner or an admin, not a browser session of a person, " +
                "or a start from another site (`forbidden`).")
            notFound("A member who is not in the organization, an id that is not one (`member_not_found`), " +
                "or cloud sessions are off on this server (`not_found`).")
            conflict(NO_APP)
        }
    }
}
