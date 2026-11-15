// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.headerText
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryLimit
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode

private const val TAG = "github"
private const val ORGANIZATION_TAG = "organization"

private const val ADMIN_APP = "Not an administrator of the instance signed in as a person: a key, an agent " +
    "or a device token is refused even when an administrator's (`forbidden`)."

private const val DELIVERY = "GitHub's id of this delivery, up to 64 hexadecimal characters and dashes; a " +
    "redelivery carries the same one."

private const val EVENT = "The kind of event, such as `installation` or `pull_request`."

private const val GH_DOWN = "GitHub did not answer (`unavailable`)."

private const val ORG_NF = "No such organization for the caller (`not_found`)."

private const val PAGE_BAD = "A `limit` outside 1 to 100, a cursor longer than 200 characters, or a cursor " +
    "no page of the list gave (`invalid_request`)."

private const val SERVICE_DOWN = "GitHub did not answer and nothing is stored yet; GitHub is not asked " +
    "again for 30 seconds (`unavailable`)."

private const val SIG = "The `sha256=` and the hex HMAC-SHA256 of the body with the App's webhook secret, " +
    "as GitHub signs it."

/**
 * The GitHub module's operations: GitHub's webhook, the App's installation and configuration, the members'
 * links to their GitHub accounts, and the repositories and teams read from the installation. The answers are
 * the module's own types.
 */
internal fun ApiCatalog.gitHubOperations() {
    tag(TAG, "The GitHub App of the instance and of each organization, the members' GitHub links and " +
        "GitHub's webhook.")
    webhookOperations()
    appOperations()
    installOperations()
    configurationOperations()
    manifestOperations()
    directoryOperations()
    gitHubLinkOperations()
}

/** GitHub's deliveries, signed with the App's webhook secret. */
private fun ApiCatalog.webhookOperations() {
    post("/webhooks/github") {
        operationId = "githubWebhook"
        tag(TAG)
        summary = "Receive a delivery from GitHub."
        description = "Called by GitHub only, with no credential: the request is signed with the App's " +
            "webhook secret and an unsigned or wrongly signed one is refused. The body is the event's JSON, " +
            "read as it came. `installation` `created` records who installed the App, which the install " +
            "callback checks; `deleted` forgets the installation. A `pull_request` `opened`, `reopened`, " +
            "`synchronize`, `ready_for_review` or `edited` asks the gate of the merge for the organization that " +
            "installed the App and publishes the `ostrom-gate` check on the head. Each delivery is handled " +
            "once: a replay is ignored, and a delivery whose handling failed is released so that GitHub's " +
            "redelivery is handled. Every other event answers 204 and changes nothing."
        parameters {
            headerText("X-Hub-Signature-256", SIG, required = true)
            headerText("X-GitHub-Event", EVENT)
            headerText("X-GitHub-Delivery", DELIVERY, required = true)
        }
        responses {
            empty("The delivery is handled, ignored or a replay.")
            error(HttpStatusCode.Unauthorized, "A missing or wrong `X-Hub-Signature-256`, or no webhook " +
                "secret stored for the App (`unauthorized`).")
            badRequest("No usable `X-GitHub-Delivery` id (`invalid_request`).")
        }
    }
}

/** The App and the organization's installation of it. */
private fun ApiCatalog.appOperations() {
    get("/api/v1/github/app") {
        operationId = "getGithubApp"
        tag(TAG)
        summary = "The organization's installation of the GitHub App."
        description = "Whether the organization installed the App, and the App's slug and page on GitHub. " +
            "`slug` and `htmlUrl` come from the App in use, `installationId` from the organization's " +
            "installation. Scope `orgs:read`, or a browser session."
        parameters {
            organizationHeader()
        }
        responses {
            ok<GitHubAppStatus>("The App and the organization's installation of it.")
            notFound(ORG_NF)
        }
    }
}

/** Installing the App on an organization. */
private fun ApiCatalog.installOperations() {
    get("/api/v1/github/app/install/start") {
        operationId = "startGithubAppInstall"
        tag(TAG)
        summary = "Start installing the GitHub App on an organization."
        description = "A browser session of an owner or an admin of the organization, started from the " +
            "console: a navigation another site started is refused. Answers a redirect to GitHub's install " +
            "page, with a state used once and for 10 minutes, bound to the session that started it. The caller " +
            "still names their own organization with `Ostrom-Org` when they belong to several."
        parameters {
            organizationHeader()
            queryText("org", "The organization to install it on (its id or short name); the caller's " +
                "organization when absent.")
        }
        responses {
            empty("A redirect to GitHub's install page.", HttpStatusCode.Found)
            error(HttpStatusCode.Forbidden, "Not an owner or an admin of the organization, or a start from " +
                "another site (`forbidden`).")
            notFound("No such organization, or one the caller is not a member of (`not_found`).")
            error(HttpStatusCode.ServiceUnavailable, "The GitHub App is not configured on this server " +
                "(`unavailable`).")
        }
    }
    get("/api/v1/github/app/install/callback") {
        operationId = "githubAppInstallCallback"
        tag(TAG)
        summary = "Finish installing the GitHub App (GitHub's return)."
        description = "GitHub's return from the install, whatever happened: this route never answers with " +
            "an error body. The browser is always sent back to the console's `/github` page, with `installed=1` " +
            "or `error=<error.type>`: `invalid_request` (a malformed callback, or an installation GitHub does " +
            "not know for this App), `unauthorized` (no session), `forbidden` (a state that is unknown, used, " +
            "expired or another session's), `installation_not_yours`, `conflict` (an installation another " +
            "organization holds), `installation_pending` (GitHub's webhook has not said who installed it yet) " +
            "and `unavailable`. The session cookie is read when there is one, because the state is bound to the " +
            "session that started the install."
        parameters {
            queryText("installation_id", "GitHub's id of the installation.")
            queryText("code", "The authorization of the person who installed.")
            queryText("state", "The state of the start.")
        }
        responses {
            empty("A redirect to the console, with the outcome in its address.", HttpStatusCode.Found)
        }
    }
}

/** The instance's App entered by hand. */
private fun ApiCatalog.configurationOperations() {
    get("/api/v1/github/app/configuration") {
        operationId = "getGithubAppConfiguration"
        tag(TAG)
        summary = "The instance's GitHub App."
        description = "The App as an administrator entered it or the manifest flow created it. A person who " +
            "administers the instance, signed in as a person; a key, an agent or a device token is refused even " +
            "an administrator's. The private key, the client secret and the webhook secret are never returned, " +
            "only whether each is set. `callbackUrls` are the URLs to register as callback URLs of the App (the " +
            "install's return, the members' links, the sign-in with GitHub)."
        responses {
            ok<GitHubAppConfigurationResponse>("The App, without its key or secrets.")
            error(HttpStatusCode.Forbidden, ADMIN_APP)
        }
    }
    put("/api/v1/github/app/configuration") {
        operationId = "saveGithubAppConfiguration"
        tag(TAG)
        summary = "Enter the instance's GitHub App, or change it."
        description = "A person who administers the instance, signed in as a person, checked before the " +
            "body is read. A `version` absent or null creates the App (the private key and the client secret " +
            "are then required); otherwise it must be the stored `version`. A null or absent secret keeps the " +
            "stored one; another `clientId` needs its own `clientSecret`, another `appId` its own private key " +
            "and keeps no webhook secret. The App is checked with GitHub (its JWT signed by the key) before it " +
            "is stored, which gives its slug and owner. Changing the App ID leaves the organizations' " +
            "installations of the former App unusable until they install the new one; changing the client ID " +
            "removes the members' links of the former client and revokes their tokens. Every replica uses the " +
            "new App within 60 seconds."
        body<SaveGitHubAppConfigurationRequest>("The App ID, the client ID, and the secrets to set or " +
            "change; the stored `version` unless it is the first save.")
        responses {
            ok<GitHubAppConfigurationResponse>("The App, saved, without its key or secrets.")
            badRequest("An App ID outside 1 to 999999999999, a client ID that is not a GitHub App's (`Iv` " +
                "then 2 to 62 of `[0-9A-Za-z._-]`), a client secret outside 20 to 256 characters or with a " +
                "space or a control character, a webhook secret over 256 characters or with one, a private key " +
                "over 16384 characters or not PEM, or a secret or a key missing where it is required " +
                "(`invalid_github_app`).")
            error(HttpStatusCode.Forbidden, ADMIN_APP)
            conflict("The `version` is not the stored one (`github_app_conflict`).")
            error(HttpStatusCode.UnprocessableEntity, "GitHub does not accept the key for this App ID " +
                "(`github_app_rejected`), or the key or the client ID is another App's (`github_app_mismatch`).")
            error(HttpStatusCode.ServiceUnavailable, GH_DOWN)
        }
    }
}

/** Creating the instance's App by GitHub's manifest flow. */
private fun ApiCatalog.manifestOperations() {
    get("/api/v1/github/app/manifest/start") {
        operationId = "startGithubAppManifest"
        tag(TAG)
        summary = "Start creating the GitHub App by the manifest flow."
        description = "An administrator of the instance in a browser session, started from the console. " +
            "Answers an HTML page that posts the manifest to GitHub, which takes it by POST only, with a " +
            "content security policy that allows no script but its own and a post to GitHub only, and " +
            "`no-store`. The state of the flow is used once, for 10 minutes, and bound to the session that " +
            "started it."
        parameters {
            queryText("owner", "The GitHub organization that will own the App; the caller's own account " +
                "when absent.")
        }
        responses {
            empty("The page that posts the manifest to GitHub.", HttpStatusCode.OK)
            badRequest("An owner that is not a GitHub login (`invalid_request`).")
            error(HttpStatusCode.Forbidden, "Not an administrator of the instance signed in as a person, or " +
                "a start from another site (`forbidden`).")
            conflict("The instance already has an App (`conflict`).")
        }
    }
    get("/api/v1/github/app/manifest/callback") {
        operationId = "githubAppManifestCallback"
        tag(TAG)
        summary = "Finish creating the GitHub App (GitHub's return)."
        description = "GitHub's return once the App is created. The state, bound to the session that " +
            "started the flow, is the proof; the session cookie is read when there is one. On success the " +
            "browser goes to the console's settings; a refusal is the usual error body."
        parameters {
            queryText("code", "GitHub's code, exchanged once for the App.")
            queryText("state", "The state of the start.")
        }
        responses {
            empty("A redirect to the console's settings.", HttpStatusCode.Found)
            badRequest("A malformed callback, or a code GitHub does not know (`invalid_request`).")
            error(HttpStatusCode.Unauthorized, "No session (`unauthorized`).")
            error(HttpStatusCode.Forbidden, "A state that is unknown, used, expired or another session's " +
                "(`forbidden`).")
            conflict("The instance already has an App (`conflict`).")
            error(HttpStatusCode.ServiceUnavailable, GH_DOWN)
        }
    }
}

/** The repositories and teams read from the organization's installation. */
private fun ApiCatalog.directoryOperations() {
    get("/api/v1/repos") {
        operationId = "listRepos"
        tag(ORGANIZATION_TAG)
        summary = "The organization's repositories."
        description = "The repositories the installation sees, by name, a page at a time, with the slugs of " +
            "the GitHub teams granted each. Scope `orgs:read`, or a browser session; an agent's token is " +
            "refused. The organization has to have installed the App: without one the list is empty. The " +
            "directory is read from GitHub, kept, and refreshed in the background."
        parameters {
            organizationHeader()
            queryText("after", "The `next` of the previous page.")
            queryLimit("The page size, 1 to 100 (100 by default).", 100)
        }
        responses {
            ok<ReposResponse>("A page of repositories; `next` is set when another follows.")
            badRequest(PAGE_BAD)
            notFound(ORG_NF)
            error(HttpStatusCode.ServiceUnavailable, SERVICE_DOWN)
        }
    }
    get("/api/v1/orgs/{org}/teams") {
        operationId = "listTeams"
        tag(ORGANIZATION_TAG)
        summary = "The teams of an organization."
        description = "The GitHub teams of the organization's installation, by slug, a page at a time, with " +
            "their members' logins and their repositories. The caller must be a member of the organization. " +
            "Scope `orgs:read`. People only: an agent, device or service token is refused."
        parameters {
            pathId("org", "The organization's id or short name.")
            queryText("after", "The `next` of the previous page.")
            queryLimit("The page size, 1 to 100 (100 by default).", 100)
        }
        responses {
            ok<TeamsResponse>("A page of teams; `next` is set when another follows.")
            badRequest(PAGE_BAD)
            notFound("No such organization, or one the caller is not a member of (`not_found`).")
            error(HttpStatusCode.ServiceUnavailable, SERVICE_DOWN)
        }
    }
}
