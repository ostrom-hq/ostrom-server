// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.presentation

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
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode

private const val TAG = "sharing"

private const val ACCOUNT_ID = "The shared account's id."

private const val GROUP_ID = "The group's id."

private const val LOGIN = "The member's login, as the group names its members."

private const val ADMIN_FORBIDDEN = "Not an owner or an admin of the organization, or a credential that may not " +
    "write (`forbidden`)."

private const val ACCOUNT_NOT_FOUND = "No such account in the caller's organization, or one the caller may not " +
    "change (`not_found`)."

private const val GROUP_NOT_FOUND = "No such group in the caller's organization (`not_found`)."

private const val MEMBER_WRITE = "Writing needs a browser session: the CLI's token only reads."

private const val ADMIN_WRITE = "An owner or an admin (`accounts:write`, browser session); a member gets 403."

private const val ADMIN_LOGIN = "$ADMIN_WRITE `login` is a member's login."

private const val BAD_GROUP = "A name that is not 1 to 120 characters without control characters, a member who is " +
    "no member of the organization, or an account that is not one of its shared accounts (`invalid_request`)."

private const val BAD_ACCOUNT = "A label that is not 1 to 120 printable characters, a limit out of its bounds, a " +
    "`sharedWith` that names no member or group of the organization, or a daily allocation that is not more " +
    "than 0 and at most 1000000 (`invalid_request`)."

private const val SHARING_PEOPLE = "Whom it is lent to: members by login, and groups as `group:<id>` (the id of a " +
    "group of `/api/v1/groups`). Empty: the whole organization. Only groups named: their members who are not " +
    "paused, no one else. A group of another organization, or a malformed id, is refused (400)."

/**
 * The sharing module's operations: the subscriptions members share and what they have left, the quota forecasts, and
 * the groups that lend them. The bodies and the answers are the module's own types.
 */
internal fun ApiCatalog.sharingOperations() {
    tag(TAG, "The subscriptions members share with their organization, what they have left, and the groups that " +
        "lend them.")
    accountOperations()
    accountChangeOperations()
    handoverOperations()
    capacityOperations()
    groupOperations()
    groupChangeOperations()
    groupMemberOperations()
}

/** Reading and enrolling the shared accounts. */
private fun ApiCatalog.accountOperations() {
    get("/api/v1/accounts") {
        operationId = "listSharedAccounts"
        tag(TAG)
        summary = "Subscriptions shared with the caller or owned by them."
        description = "Read with `accounts:read` (the token of `ostrom login` holds it, for `ostrom status`) or " +
            "a key's `team:read`; an agent's or a device's credential: 403. At most 100 accounts, the one " +
            "whose quota resets last first and those with no reset known last; `next` is never set. An " +
            "administrator or an account's owner sees it whole; for another member's account, a neutral " +
            "label (`Account 1`...), its provider, status, priority, quota windows and requests in flight, " +
            "its concurrency and utilization ceiling, and none of its e-mail, provider identity, owner or " +
            "other limits."
        parameters {
            organizationHeader()
        }
        responses {
            ok<SharedAccountsResponse>("The organization's accounts, as the caller may see each.")
            notFound("The caller is no member of an organization the request can act in (`not_found`).")
        }
    }
    post("/api/v1/accounts") {
        operationId = "enrollSharedAccount"
        tag(TAG)
        summary = "Enroll a personal subscription (credentials encrypted on the server)."
        description = "$MEMBER_WRITE Any member shares a subscription of their own. The token is the value of " +
            "the secret `credentialSecret` names (the caller's own secret, else, for an admin, the " +
            "organization's), read on the server and kept sealed, as a long-lived token that is never " +
            "renewed; enrolling the same secret again replaces its token and applies the sharing the form " +
            "gives. A body without `credentialSecret` is the tokens' body (`provider`, `label`, `email`, " +
            "`providerAccountId`, `accessToken`, `refreshToken`, `expiresAt` and `limits`), which imports and " +
            "the end-to-end tests send. `provider` is `claude` or `codex`. 400 for a label that is not 1 to " +
            "120 printable characters, a provider or a limit that is none, a `sharedWith` that names no " +
            "member or group, or a daily allocation outside 0 (excluded) to 1000000; 404 `not_found` for a " +
            "secret the caller has none of that name; 409 for the 100 accounts an organization shares at " +
            "most, or an account another member already shares; 403 `account_in_use` for an account an " +
            "admin left waiting for its handover."
        parameters {
            organizationHeader()
        }
        body<EnrollSharedAccountRequest>("The provider, the name admins see, the secret holding the token, " +
            "whom it is lent to and what it may serve per UTC day, in USD.")
        responses {
            ok<SharedAccountResponse>("The account, enrolled.", HttpStatusCode.Created)
            badRequest(BAD_ACCOUNT)
            error(HttpStatusCode.Forbidden, "The credential may not write, or the account was imported and " +
                "waits for an admin's handover (`account_in_use`).")
            notFound("No secret of that name the caller may lend, or no organization the caller can act in " +
                "(`not_found`).")
            conflict("The organization shares 100 accounts already, or another member shares this account " +
                "(`conflict`).")
        }
    }
}

/** Changing and removing a shared account. */
private fun ApiCatalog.accountChangeOperations() {
    patch("/api/v1/accounts/{id}") {
        operationId = "updateSharedAccount"
        tag(TAG)
        summary = "Change sharing, allocation, pause."
        description = "$MEMBER_WRITE An owner or an admin changes any account of the organization, a member the " +
            "accounts they shared; any other account is a 404. Each field given replaces the current one: " +
            "`label`, `priority` (-100 to 100), `paused` (true switches the account off, false back on; an " +
            "account waiting for a reconnection or on the wrong account keeps its status), `sharedWith`, " +
            "`dailyAllocationUsd` (more than 0, at most 1000000) and `limits`, whose keys replace theirs " +
            "while the others stay (`maxConcurrent` 0 to 64, `utilizationCeiling`, `monthlyBudgetMicros`, " +
            "`allowedMembers`, `dailyBudgetMicros`; a key set to null clears a budget)."
        parameters {
            organizationHeader()
            pathId("id", ACCOUNT_ID)
        }
        body<UpdateAccountRequest>("The fields to change: `sharedWith` is $SHARING_PEOPLE")
        responses {
            ok<SharedAccountResponse>("The account after the change.")
            badRequest(BAD_ACCOUNT)
            notFound(ACCOUNT_NOT_FOUND)
        }
    }
    delete("/api/v1/accounts/{id}") {
        operationId = "removeSharedAccount"
        tag(TAG)
        summary = "Stop sharing and delete the credentials."
        description = "$MEMBER_WRITE An owner or an admin removes any account of the organization, a member the " +
            "accounts they shared; any other account is a 404. The account's sealed credentials are deleted " +
            "with it."
        parameters {
            organizationHeader()
            pathId("id", ACCOUNT_ID)
        }
        responses {
            empty("The account and its credentials are deleted.")
            notFound(ACCOUNT_NOT_FOUND)
        }
    }
}

/** Who renews an account's tokens. */
private fun ApiCatalog.handoverOperations() {
    post("/api/v1/accounts/{id}/handover") {
        operationId = "handOverSharedAccount"
        tag(TAG)
        summary = "Give Ostrom refresh ownership of an imported account (admins)."
        description = "TeamCode's refresh-ownership handover. An imported account waits (`refreshOwnership` " +
            "`pending_handover`) because the source it came from may still renew its tokens, and a rotating " +
            "refresh token sent by two refreshers disconnects the account. Once every other refresher has " +
            "stopped, an owner or admin hands it over: `refreshOwnership` becomes `ostrom`, and the account " +
            "serves and is renewed by Ostrom. Also takes over an `access_only` account. An account already " +
            "owned is answered as it is. A browser session, or an administrator signed in on their " +
            "workstation (`ostrom login --admin`, recorded in the audit log), hands over. A member who is " +
            "not an admin gets 403 `account_handover_forbidden` on an account they shared, 404 on any " +
            "other; an account of another organization is 404. 409 `account_refreshing` while a refresh of " +
            "the account is in flight. Never changes `status` or `paused`. Served under " +
            "`sharing.handover.v1`."
        parameters {
            organizationHeader()
            pathId("id", ACCOUNT_ID)
        }
        responses {
            ok<SharedAccountResponse>("The account after the change.")
            error(HttpStatusCode.Forbidden, "A member who shared the account but is not an admin " +
                "(`account_handover_forbidden`), or a credential that may not hand over (`forbidden`).")
            notFound(ACCOUNT_NOT_FOUND)
            conflict("A refresh of the account is in flight or left it uncertain (`account_refreshing`).")
        }
    }
    post("/api/v1/accounts/{id}/access-only") {
        operationId = "makeSharedAccountAccessOnly"
        tag(TAG)
        summary = "Serve an imported account with its access token only, never renewing it (admins)."
        description = "TeamCode's access-only trial mode: the account serves with the access token it holds " +
            "and is never renewed (`refreshOwnership` `access_only`), so the source that renews it keeps " +
            "working. Once the access token is within 5 minutes of its expiry the account serves nothing " +
            "until it is imported again with fresh tokens (or handed over). Only a browser session of an " +
            "owner or an admin; the other answers are the handover's. 409 `account_refreshing` while a " +
            "refresh is in flight. Served under `sharing.handover.v1`."
        parameters {
            organizationHeader()
            pathId("id", ACCOUNT_ID)
        }
        responses {
            ok<SharedAccountResponse>("The account after the change.")
            error(HttpStatusCode.Forbidden, "A member who shared the account but is not an admin " +
                "(`account_handover_forbidden`), or a credential that may not write (`forbidden`).")
            notFound(ACCOUNT_NOT_FOUND)
            conflict("A refresh of the account is in flight or left it uncertain (`account_refreshing`).")
        }
    }
}

/** What the accounts have left, and the forecasts. */
private fun ApiCatalog.capacityOperations() {
    get("/api/v1/capacity") {
        operationId = "getCapacity"
        tag(TAG)
        summary = "Remaining capacity of shared subscriptions."
        description = "Read with `accounts:read` or a key's `team:read`, as `listSharedAccounts`: each account " +
            "as the caller may see it, and for each provider its pool, the accounts in it, how many can " +
            "serve now and the requests in flight."
        parameters {
            organizationHeader()
        }
        responses {
            ok<CapacityResponse>("The accounts, the pools by provider, and when it was computed.")
            notFound("The caller is no member of an organization the request can act in (`not_found`).")
        }
    }
    get("/api/v1/forecasts") {
        operationId = "getForecasts"
        tag(TAG)
        summary = "Quota forecasts of one shared account (`capacity.forecasts.v1`)."
        description = "TeamCode's Forecasts page. For each current provider window of the account: its latest " +
            "reading, the readings of the last hour cut into segments (a segment never joins two readings " +
            "across a reset, a drop in utilization or more than five minutes without a reading), the rate " +
            "learned from the latest segment (at least three readings over at least a minute, never across " +
            "a drop) and when, at that rate, the window reaches the account's `utilizationCeiling` " +
            "(`pressureThreshold`). An unknown state is said, never drawn as 0 %: `unknown` (no reset " +
            "known, or no rate learned yet), `stale` (the reading is older than five minutes or its reset " +
            "has passed), `exhausted` (at or above the threshold), `idle` (a rate of zero), `reset_first` " +
            "or `exhausts_first`. Without `account`, the first account by id. Owners and admins of the " +
            "organization only, signed in (a browser session, the unlocked app or the token of `ostrom " +
            "login`, which holds `accounts:read`); a member, a key, an agent or a device credential: 403. " +
            "An account of another organization: 404; `account` given twice: 400 `invalid_account`. " +
            "Bounded: at most 64 accounts listed, 128 windows and 8,192 readings, `truncated` true past a " +
            "bound. Never names a credential or a provider identity beyond the account's label and " +
            "e-mail. Answered with `Cache-Control: no-store`."
        parameters {
            organizationHeader()
            queryText("account", "The shared account to forecast, by id; absent, the first account by id.")
        }
        responses {
            ok<ForecastSnapshotResponse>("The account's windows with their readings and forecasts, and the " +
                "accounts of the organization.")
            badRequest("`account` given more than once (`invalid_account`).")
            error(HttpStatusCode.Forbidden, "Not an owner or an admin signed in: a member, a key, an agent or " +
                "a device (`forbidden`).")
            notFound("No such account in the caller's organization (`not_found`).")
        }
    }
}

/** Reading and creating groups. */
private fun ApiCatalog.groupOperations() {
    get("/api/v1/groups") {
        operationId = "listGroups"
        tag(TAG)
        summary = "Sharing groups."
        description = "Every member of the organization reads its groups (`accounts:read`): name, members by " +
            "login (a member who left is named by member id), the ids of the shared accounts lent to the " +
            "group, and the members paused now. At most 100 groups, by name. A person outside the " +
            "organization named by `Ostrom-Org` gets 404."
        parameters {
            organizationHeader()
        }
        responses {
            ok<GroupsResponse>("The organization's groups, by name.")
            notFound("The caller is no member of an organization the request can act in (`not_found`).")
        }
    }
    post("/api/v1/groups") {
        operationId = "createGroup"
        tag(TAG)
        summary = "Create a group."
        description = "$ADMIN_WRITE `name` is trimmed, 1 to 120 characters, unique in the organization " +
            "whatever the case (409). `members` are logins of members of the organization and `accounts` " +
            "ids of its shared accounts, else 400. At most 1000 members per group and 100 groups per " +
            "organization (409)."
        parameters {
            organizationHeader()
        }
        body<CreateGroupRequest>("The name, the members by login and the accounts lent to the group by id.")
        responses {
            ok<GroupResponse>("The group, created.", HttpStatusCode.Created)
            badRequest(BAD_GROUP)
            error(HttpStatusCode.Forbidden, ADMIN_FORBIDDEN)
            conflict("Another group has this name, or the organization has 100 groups (`conflict`).")
        }
    }
}

/** Reading, changing and deleting one group. */
private fun ApiCatalog.groupChangeOperations() {
    get("/api/v1/groups/{id}") {
        operationId = "getGroup"
        tag(TAG)
        summary = "A group."
        description = "Every member reads it. A group of another organization, a deleted one or an id that is " +
            "no UUID is a 404."
        parameters {
            organizationHeader()
            pathId("id", GROUP_ID)
        }
        responses {
            ok<GroupResponse>("The group, with its members, its accounts and the members paused now.")
            notFound(GROUP_NOT_FOUND)
        }
    }
    patch("/api/v1/groups/{id}") {
        operationId = "updateGroup"
        tag(TAG)
        summary = "Change a group."
        description = "$ADMIN_WRITE Each field given replaces the current one: `name` (409 when another group " +
            "has it), `accounts` (all the accounts lent, an empty list lends nothing). Members change " +
            "through `/members/{login}`. A group of another organization is a 404."
        parameters {
            organizationHeader()
            pathId("id", GROUP_ID)
        }
        body<UpdateGroupRequest>("The new name and the accounts lent to the group by id; a field left out " +
            "stays.")
        responses {
            ok<GroupResponse>("The group after the change.")
            badRequest(BAD_GROUP)
            error(HttpStatusCode.Forbidden, ADMIN_FORBIDDEN)
            notFound(GROUP_NOT_FOUND)
            conflict("Another group has this name (`conflict`).")
        }
    }
    delete("/api/v1/groups/{id}") {
        operationId = "deleteGroup"
        tag(TAG)
        summary = "Delete a group."
        description = "$ADMIN_WRITE Its members and the accounts lent to it go with it; the accounts themselves " +
            "stay. A group of another organization is a 404."
        parameters {
            organizationHeader()
            pathId("id", GROUP_ID)
        }
        responses {
            empty("The group is deleted.")
            error(HttpStatusCode.Forbidden, ADMIN_FORBIDDEN)
            notFound(GROUP_NOT_FOUND)
        }
    }
}

/** The members of a group. */
private fun ApiCatalog.groupMemberOperations() {
    put("/api/v1/groups/{id}/members/{login}") {
        operationId = "addGroupMember"
        tag(TAG)
        summary = "Add a member to a group."
        description = "$ADMIN_WRITE `login` names a member of the organization (else 404). Adding a member " +
            "already in the group changes nothing, a pause included; a group of 1000 members takes no more " +
            "(400). A group of another organization is a 404."
        parameters {
            organizationHeader()
            pathId("id", GROUP_ID)
            pathId("login", "The login of a member of the organization.")
        }
        responses {
            empty("The member is in the group.")
            badRequest("The group has 1000 members already (`invalid_request`).")
            error(HttpStatusCode.Forbidden, ADMIN_FORBIDDEN)
            notFound("No such group, or no member of the organization with this login (`not_found`).")
        }
    }
    delete("/api/v1/groups/{id}/members/{login}") {
        operationId = "removeGroupMember"
        tag(TAG)
        summary = "Remove a member from a group."
        description = "$ADMIN_WRITE `login` is the member's login, or the member id the group shows for one who " +
            "left the organization. 404 when they are not in the group."
        parameters {
            organizationHeader()
            pathId("id", GROUP_ID)
            pathId("login", LOGIN)
        }
        responses {
            empty("The member is out of the group.")
            error(HttpStatusCode.Forbidden, ADMIN_FORBIDDEN)
            notFound("No such group, or the member is not in it (`not_found`).")
        }
    }
    post("/api/v1/groups/{id}/members/{login}/pause") {
        operationId = "pauseGroupMember"
        tag(TAG)
        summary = "Pause a member in a group."
        description = "$ADMIN_LOGIN `until` is an ISO 8601 instant (else 400): the group lends nothing to the " +
            "member until then, and an instant already past ends the pause. 404 when they are not in the " +
            "group."
        parameters {
            organizationHeader()
            pathId("id", GROUP_ID)
            pathId("login", LOGIN)
        }
        body<PauseGroupMemberRequest>("The instant, in ISO 8601, until which the group lends nothing to the " +
            "member.")
        responses {
            empty("The member is paused until then.")
            badRequest("A missing `until`, or one that is no ISO 8601 instant (`invalid_request`).")
            error(HttpStatusCode.Forbidden, ADMIN_FORBIDDEN)
            notFound("No such group, or the member is not in it (`not_found`).")
        }
    }
}
