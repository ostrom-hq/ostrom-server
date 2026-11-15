// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryNumber
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode

private const val TAG = "auth"

private const val BROWSER_ONLY = "(browser session only)"

/** The caller's account, the CLI's device login, the browser sessions and the personal tokens. */
internal fun ApiCatalog.accountOperations() {
    deviceLoginOperations()
    deviceAnswerOperations()
    meOperations()
    signOutOperations()
    tokenOperations()
}

/** `ostrom login`: the CLI starts and polls. */
private fun ApiCatalog.deviceLoginOperations() {
    post("/api/v1/auth/device") {
        operationId = "startDeviceLogin"
        tag(TAG)
        summary = "Start the device flow (CLI sign-in)."
        description = "Without a body, the ordinary sign-in of `ostrom login`. With `scopes`, `ostrom login " +
            "--admin`: the governance write scopes no other token holds. The browser's page shows every scope its " +
            "token will hold (`scopes` of `getDeviceLogin`), only an owner or admin of the organization may " +
            "approve them, and the token that comes out is bound to that organization, expires in 8 hours at most " +
            "and is never refreshed. Each governance write it makes is recorded in the organization's audit log " +
            "before it happens. A server older than this answers an ordinary token, whose `scopes` tell the CLI."
        body<DeviceCodeRequest>("What the CLI is, the scopes it asks for and its installation.", required = false)
        responses {
            ok<DeviceCodeResponse>("The codes to show and to poll with.")
            tooManyAttempts()
        }
    }
    post("/api/v1/auth/device/token") {
        operationId = "pollDeviceLogin"
        tag(TAG)
        summary = "Exchange a confirmed device code for a token (RFC 8628 section 3.5)."
        description = "428 `authorization_pending` while nobody approved it yet (poll again after the interval); " +
            "400 `slow_down` (poll less often), `access_denied` (the sign-in was refused) or `expired_token` (the " +
            "code expired or was used; start again), in `error.type`; 200 with the token once approved."
        body<DeviceTokenRequest>("The device code.")
        responses {
            ok<AuthTokenResponse>("The token, once the sign-in is approved.")
            badRequest("`slow_down`, `access_denied` or `expired_token` (RFC 8628), or a malformed request.")
            error(HttpStatusCode.PreconditionRequired, "`authorization_pending`: not approved yet, poll again " +
                "after the interval.")
        }
    }
}

/** `ostrom login`: the console shows the login and answers it. */
private fun ApiCatalog.deviceAnswerOperations() {
    get("/api/v1/auth/device") {
        operationId = "getDeviceLogin"
        tag(TAG)
        summary = "The pending `ostrom login` the person is about to answer $BROWSER_ONLY."
        parameters {
            queryText("userCode", "The code the CLI shows.")
        }
        responses {
            ok<DeviceAuthorizationResponse>("The login, and every scope its token will hold.")
            badRequest("No `userCode` (`invalid_request`).")
            notFound("Unknown or expired code (`not_found`).")
            tooManyAttempts()
        }
    }
    post("/api/v1/auth/device/answer") {
        operationId = "answerDeviceLogin"
        tag(TAG)
        summary = "Approve or deny an `ostrom login` $BROWSER_ONLY."
        description = "Approving an `ostrom login --admin` binds its token to an organization the person owns or " +
            "administers. The organization is read after the recent sign-in check: the one `Ostrom-Org` names, " +
            "else the person's only one. A person in several organizations names it, or gets 409 `org_required`. " +
            "An `Ostrom-Org` value that is not the id of one of their organizations, an organization they belong " +
            "to without owning or administering it, and a person in no organization are 403 `forbidden`, never " +
            "404. The ordinary `ostrom login` and a denial read no organization."
        parameters {
            organizationHeader()
        }
        body<AnswerDeviceRequest>("The code, and whether the person approves it.")
        responses {
            empty("Answered.")
            stepUp()
            conflict("Approving `ostrom login --admin` without `Ostrom-Org`, for a person in several " +
                "organizations (`org_required`).")
            notFound("Unknown or expired code (`not_found`).")
            tooManyAttempts()
        }
    }
}

/** Who the caller is. */
private fun ApiCatalog.meOperations() {
    get("/api/v1/auth/me") {
        operationId = "getMe"
        tag(TAG)
        summary = "The caller: login, member, organization."
        description = "`Ostrom-Org` is an option here: `member` and `organization` are those of the organization " +
            "it names when the caller is a member of it, and absent otherwise (no header, not an id, not a " +
            "membership): never 409 nor 404. The account (e-mails, ways in) is read at " +
            "`/api/v1/auth/me/account`."
        parameters {
            organizationHeader()
        }
        responses {
            ok<MeResponse>("The caller, with the capabilities of the credential and the CSRF token of a session.")
        }
    }
    get("/api/v1/auth/me/account") {
        operationId = "getMyAccount"
        tag(TAG)
        summary = "The signed-in person's account: e-mails and ways in (scope `profile:read`)."
        responses {
            ok<AccountResponse>("The account.")
        }
    }
    get("/api/v1/auth/me/audit") {
        operationId = "listMyAudit"
        tag(TAG)
        summary = "The person's account-level signed audit events, newest first (scope `profile:read`)."
        parameters {
            queryNumber("before", "The `sequence` of the last event of the previous page.")
            queryNumber("limit", "1 to 200, 50 by default.")
        }
        responses {
            ok<AuditEventsResponse>("A page of events, `nextBefore` set when another may follow.")
        }
    }
}

/** Signing out, and the browser sessions. */
private fun ApiCatalog.signOutOperations() {
    post("/api/v1/auth/logout") {
        operationId = "logout"
        tag(TAG)
        summary = "Sign out."
        description = "Revokes the calling token or browser session, deletes the push devices registered with it " +
            "(the app receives no more yes requests), and for a browser session expires the " +
            "`__Host-ostrom_session` and `__Host-ostrom_csrf` cookies."
        responses {
            empty("Signed out.")
        }
    }
    post("/api/v1/auth/logout/everywhere") {
        operationId = "logoutEverywhere"
        tag(TAG)
        summary = "Sign out of every browser session of the caller $BROWSER_ONLY."
        description = "Ends every browser session of the signed-in person, expires this browser's " +
            "`__Host-ostrom_session` and `__Host-ostrom_csrf` cookies, and deletes the push devices registered " +
            "with them. Tokens are not revoked."
        responses {
            empty("Signed out everywhere.")
        }
    }
    get("/api/v1/auth/sessions") {
        operationId = "listAuthSessions"
        tag(TAG)
        summary = "Signed-in sessions of the caller."
        description = "A browser session, or the person's own CLI sign-in. `current` marks the one asking."
        responses {
            ok<AuthSessionsResponse>("The live sessions.")
        }
    }
    delete("/api/v1/auth/sessions/{id}") {
        operationId = "revokeAuthSession"
        tag(TAG)
        summary = "Revoke a signed-in session."
        description = "A browser session, or the person's own CLI sign-in. Ending the session that makes the " +
            "request is signing out."
        parameters {
            pathId("id", "The session.")
        }
        responses {
            empty("Revoked.")
            notFound("No such session of the caller (`not_found`).")
        }
    }
}

/** The personal and CLI tokens. */
private fun ApiCatalog.tokenOperations() {
    get("/api/v1/tokens/scopes") {
        operationId = "listTokenScopes"
        tag(TAG)
        summary = "The scopes a personal token may be given $BROWSER_ONLY."
        description = "`POST /api/v1/tokens` refuses any other."
        responses {
            ok<TokenScopesResponse>("The scopes, with what each allows.")
        }
    }
    get("/api/v1/tokens") {
        operationId = "listTokens"
        tag(TAG)
        summary = "The person's live personal and CLI tokens, newest first $BROWSER_ONLY."
        responses {
            ok<TokensResponse>("The tokens, without their secrets.")
        }
    }
    post("/api/v1/tokens") {
        operationId = "createToken"
        tag(TAG)
        summary = "Create a personal token: the answer is the only one with its secret $BROWSER_ONLY."
        body<CreateTokenRequest>("The name, the scopes and the lifetime (1 to 366 days, none by default).")
        responses {
            ok<CreatedTokenResponse>("The token, and its secret, given once.", HttpStatusCode.Created)
            badRequest("A name, scopes or lifetime the server refuses (`invalid_request`).")
            stepUp()
            conflict("Too many live tokens: revoke one first (`conflict`).")
        }
    }
    delete("/api/v1/tokens/{id}") {
        operationId = "revokeToken"
        tag(TAG)
        summary = "Revoke a token $BROWSER_ONLY."
        parameters {
            pathId("id", "The token.")
        }
        responses {
            empty("Revoked.")
            notFound("No such token of the caller (`not_found`).")
        }
    }
    post("/api/v1/tokens/{id}/rotate") {
        operationId = "rotateToken"
        tag(TAG)
        summary = "Replace a token's secret: the old one stops working $BROWSER_ONLY."
        parameters {
            pathId("id", "The token.")
        }
        responses {
            ok<CreatedTokenResponse>("The token, and its new secret, given once.", HttpStatusCode.Created)
            stepUp()
            notFound("No such token of the caller (`not_found`).")
        }
    }
}
