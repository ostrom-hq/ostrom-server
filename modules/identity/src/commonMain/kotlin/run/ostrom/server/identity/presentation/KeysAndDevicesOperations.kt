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
import run.ostrom.server.kernel.http.docs.queryText
import run.ostrom.server.kernel.http.docs.rateLimited
import io.ktor.http.HttpStatusCode

private const val TAG = "auth"

private const val AUDIT_TAG = "audit"

private const val NOTIFICATIONS_TAG = "notifications"

/** The organization's service keys, the workstations where people are signed in, its audit log, the app's switches. */
internal fun ApiCatalog.keysAndDevicesOperations() {
    serviceKeyOperations()
    deviceOperations()
    deviceChangeOperations()
    orgAuditOperations()
    notificationPreferencesOperations()
}

/** The service keys of the organization (its owners and admins). */
private fun ApiCatalog.serviceKeyOperations() {
    get("/api/v1/service-keys") {
        operationId = "listServiceKeys"
        tag(TAG)
        summary = "Service keys (admins)."
        description = "The live service keys of the organization, newest first, without their secrets. An owner " +
            "or an admin of the organization `Ostrom-Org` names (or the caller's only one), with a browser " +
            "session or the person's own CLI sign-in, never a service key, an agent's token or a personal token."
        parameters {
            organizationHeader()
        }
        responses {
            ok<ServiceKeysResponse>("The keys.")
        }
    }
    post("/api/v1/service-keys") {
        operationId = "createServiceKey"
        tag(TAG)
        summary = "Create a service key; its secret is returned once."
        description = "An owner or an admin of the organization, with a browser session or the person's own CLI " +
            "sign-in, after a sign-in less than 5 minutes old."
        parameters {
            organizationHeader()
        }
        body<CreateServiceKeyRequest>(
            "The name, the scopes (1 to 32) and the lifetime (1 to 366 days, none by default).",
        )
        responses {
            ok<ServiceKeyResponse>("The key, and its secret, given once.", HttpStatusCode.Created)
            badRequest("A name, scopes or lifetime the server refuses (`invalid_request`).")
            stepUp()
            conflict("Too many live service keys: delete one first (`conflict`).")
        }
    }
    delete("/api/v1/service-keys/{id}") {
        operationId = "deleteServiceKey"
        tag(TAG)
        summary = "Delete a service key."
        description = "An owner or an admin of the organization, with a browser session or the person's own CLI " +
            "sign-in."
        parameters {
            organizationHeader()
            pathId("id", "The key.")
        }
        responses {
            empty("Deleted.")
            notFound("No such key in the organization (`not_found`).")
        }
    }
}

/** The workstations where people are signed in. */
private fun ApiCatalog.deviceOperations() {
    get("/api/v1/devices") {
        operationId = "listDevices"
        tag(TAG)
        summary = "Workstations where the caller is signed in."
        description = "A browser session only (with `devices.v1`, also the CLI's own sign-in, see below). Not " +
            "paged. The caller's CLI tokens (`kind: cli`), then, when `Ostrom-Org` names an organization, its " +
            "live tokens of `ostrom login --admin` (`kind: admin`, with `orgId`, `scopes` and `expiresAt`): the " +
            "caller's own for a member, every member's for an owner or an admin. Such a token is bound to its " +
            "organization and only listed when the request names it; the console asks once per organization. A " +
            "member of another organization gets 404. With `devices.v1`: the caller's registered devices (one per " +
            "installation of the CLI, with `createdAt`, `lastLoginAt`, `activeSessions`, `current` and " +
            "`osVersion`), then the tokens of CLIs older than that, each on its own; the CLI's own sign-in may " +
            "read it too (`ostrom devices list`), an agent's token or a service key may not (403)."
        parameters {
            organizationHeader()
        }
        responses {
            ok<DevicesResponse>("The workstations.")
            notFound("The caller is not a member of the organization `Ostrom-Org` names (`not_found`).")
        }
    }
}

/** Renaming a workstation, signing it out, and an administrator's view of a member's. */
private fun ApiCatalog.deviceChangeOperations() {
    patch("/api/v1/devices/{id}") {
        operationId = "renameDevice"
        tag(TAG)
        summary = "Rename a device."
        description = "With `devices.v1`. The caller's own registered device, from a browser session or the CLI's " +
            "sign-in; an owner or an admin also renames a member's (`Ostrom-Org` naming their organization), " +
            "audited there. 1 to 120 characters, no control character."
        parameters {
            organizationHeader()
            pathId("id", "The device.")
        }
        body<RenameDeviceRequest>("The new name.")
        responses {
            ok<DeviceResponse>("The device renamed.")
            badRequest("A name the server refuses (`invalid_device_name`).")
            notFound("Another member's device, a token listed on its own or an unknown id (`device_not_found`).")
        }
    }
    delete("/api/v1/devices/{id}") {
        operationId = "deleteDevice"
        tag(TAG)
        summary = "Sign a workstation out."
        description = "A browser session only (with `devices.v1`, also the CLI's own sign-in, see below). Revokes " +
            "one of the caller's CLI tokens, or a token of `ostrom login --admin` of the organization " +
            "`Ostrom-Org` names: a member their own, an owner or an admin any member's (a leaked token, a member " +
            "who left). Audited in that organization, naming the actor and the token's owner. With `devices.v1`: " +
            "a registered device's id removes the device and signs every one of its sign-ins out at once (a " +
            "copied configuration included); the CLI's own sign-in may call it too, and removing the device it " +
            "runs on signs it out (`ostrom login` again). Its next sign-in registers the installation again, " +
            "under a new id."
        parameters {
            organizationHeader()
            pathId("id", "The device, or the token listed on its own.")
        }
        responses {
            empty("Signed out.")
            notFound("Another member's for a member, a token of another organization, a personal token, a " +
                "service key or an unknown id (`not_found`).")
        }
    }
    get("/api/v1/members/{memberId}/devices") {
        operationId = "listMemberDevices"
        tag(TAG)
        summary = "A member's devices (owner or admin)."
        description = "With `devices.v1`. An owner or an admin of the organization `Ostrom-Org` names reads the " +
            "devices of one of its members, as `GET /api/v1/devices` answers them to that member (`current` " +
            "always false). A member gets 403."
        parameters {
            organizationHeader()
            pathId("memberId", "The member.")
        }
        responses {
            ok<DevicesResponse>("The member's workstations.")
            notFound("A member of another organization, or an unknown id (`not_found`).")
        }
    }
}

/** The organization's signed audit log. */
private fun ApiCatalog.orgAuditOperations() {
    tag(AUDIT_TAG, "The organization's signed audit log: what changed, by whom, and the proof it was not altered.")
    get("/api/v1/audit") {
        operationId = "listAudit"
        tag(AUDIT_TAG)
        summary = "Audit log (admins)."
        description = "The organization's signed audit log, newest first, 50 lines a page: governance changes " +
            "(budgets, model policy, inference settings, MCP servers, assignments and policies), organization " +
            "and membership changes, the import cutover and the deletion of the organization. Readable by an " +
            "owner or an admin only (403 otherwise), with a browser session or a token holding `audit:read`; " +
            "another organization's log is never read (404). `actor` is a person's login (their id once they no " +
            "longer exist), or `<kind>:<id>` for a credential that is not a person's (`service:…`, `agent:…`), " +
            "or `server:ostrom` for the server itself; the `actor` filter takes a login only. `subject` is " +
            "`<type>:<id>`; `meta` holds the event's facts as strings, with `credential` (`browser`, " +
            "`api_token`…) for a change made through the API. `next` is absent on the last page. Each line is " +
            "checked as it is read, against the line before it in the chain even when a filter leaves that one " +
            "out: `integrity` says what was found, and the line carries what a client needs to check it again. " +
            "The canonical form (sorted keys, no spaces, `null` for an absent value, `v` 1) holds `action` " +
            "(`event`), `actorId`, `details` (`meta`), `id`, `occurredAt` (`at`), `orgId`, `previousHash`, " +
            "`sequence`, `targetId` and `targetType` (`subject` split at its first colon); `hash` is its " +
            "SHA-256 and `signature` verifies with the key `keyId` (`GET /api/v1/instance/keys`, past keys " +
            "included). Erasing the newest lines of a chain is not seen."
        parameters {
            organizationHeader()
            queryText("actor", "Only this person's events, by login.")
            queryText("event", "Only this event, by its exact name (`budget.changed`).")
            queryText("before", "The `next` of the previous page (400 when it is not one).")
        }
        responses {
            ok<OrgAuditPageResponse>("A page of events, `next` set when another may follow.")
            badRequest("`before` is not the `next` of a page (`invalid_request`).")
            rateLimited()
        }
    }
}

/** What the phone app tells its person about. */
private fun ApiCatalog.notificationPreferencesOperations() {
    get("/api/v1/me/notifications") {
        operationId = "getNotificationPreferences"
        tag(NOTIFICATIONS_TAG)
        summary = "Notification preferences of the caller."
        description = "Read with the scope `profile:read`, which a browser session holds."
        responses {
            ok<NotificationPreferencesBody>("The switches, and the quiet hours if any.")
        }
    }
    put("/api/v1/me/notifications") {
        operationId = "setNotificationPreferences"
        tag(NOTIFICATIONS_TAG)
        summary = "Set the notification preferences of the caller."
        description = "Written with the scope `notifications:write`, which a browser session holds."
        body<NotificationPreferencesBody>("Every switch, and the quiet hours if any.")
        responses {
            ok<NotificationPreferencesBody>("The preferences saved.")
            badRequest("Quiet hours that are not two different `HH:MM` and a time zone name (`invalid_request`).")
        }
    }
}
