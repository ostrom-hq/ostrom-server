// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.presentation

import run.ostrom.core.api.models.NotificationSettingChange
import run.ostrom.core.api.models.NotificationSettings
import run.ostrom.core.api.models.PushTestResults
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
import io.ktor.http.HttpStatusCode

/** The tag the module declares, and its other operations (Slack's) share. */
private const val TAG = "notifications"

private const val PHONE_CALLER = "Scope `notifications:write`: the phone app's token or a browser session, never " +
    "a personal or CLI token."

private const val NO_ORG = "The caller is no member of an organization the request can act in (`not_found`)."

private const val INTERACTIVE = "an interactive login (a browser session, the unlocked app or `ostrom login`)"

private const val PERSON_ONLY = "Not a person's interactive login: a service key, an agent, a device or a " +
    "personal token has no person to notify (`forbidden`)."

/**
 * The notifications module's operations: the phones a member registers for push, the member's own notification
 * switches, and the test push. The bodies and the answers are the contract's models or the module's own types, which
 * follow the contract field for field.
 */
internal fun ApiCatalog.notificationsOperations() {
    tag(TAG, "What a member is told about, and where: the phones that receive push, the switches per channel and " +
        "kind, and the Slack link.")
    registerOperations()
    deviceOperations()
    settingsOperations()
    pushTestOperations()
}

/** Registering a phone. */
private fun ApiCatalog.registerOperations() {
    post("/api/v1/push-devices") {
        operationId = "registerPushDevice"
        tag(TAG)
        summary = "Register a phone for push notifications."
        description = "$PHONE_CALLER The phone is registered in the organization `Ostrom-Org` names, or the " +
            "caller's only one (409 `org_required` when they belong to several and name none). One device per " +
            "push token in an organization: the same token registered again keeps its device (its `id` and " +
            "`createdAt`) and belongs to the member who registered it last. A member keeps their ten most " +
            "recently registered phones. The push token is never answered back. The phone sends its FCM " +
            "registration token (`provider: fcm`, iOS and Android alike) with its P-256 public key " +
            "(`publicKey`, SubjectPublicKeyInfo DER in base64), at every start of the app and when FCM " +
            "rotates the token: each push is encrypted for that key, so the provider sees a token and an " +
            "opaque blob only. Who sends the pushes is the instance's `push` settings section (`provider` " +
            "`off` or `firebase`); while it is `off`, phones register and nothing is sent. A provider other " +
            "than `fcm` (`expo` included, which is refused since Expo was removed), a platform other than " +
            "`ios` and `android`, a token that is not an FCM registration token, a malformed locale, or a " +
            "missing or invalid public key is a 400; a phone an older app registered with `expo` is never " +
            "notified until the app registers FCM."
        parameters {
            organizationHeader()
        }
        body<PushDeviceRequest>("The FCM registration token, the provider (`fcm`), the platform (`ios` or " +
            "`android`), the phone's language, and its P-256 public key, required with `fcm`.")
        responses {
            ok<PushDeviceDto>("The phone, without its push token.", HttpStatusCode.Created)
            badRequest("Not the FCM registration token and public key of an iOS or Android phone " +
                "(`invalid_request`).")
            notFound(NO_ORG)
        }
    }
}

/** Listing and removing the caller's phones. */
private fun ApiCatalog.deviceOperations() {
    get("/api/v1/push-devices") {
        operationId = "listPushDevices"
        tag(TAG)
        summary = "The caller's phones registered for push notifications."
        description = "$PHONE_CALLER The caller's own phones in the organization `Ostrom-Org` names, or their " +
            "only one, the most recently seen first (at most ten: see `registerPushDevice`). Another " +
            "member's phones are never listed. A push token and a public key are never answered back. Each " +
            "phone's `id` is what `deletePushDevice` takes, so a member can see the phones that are notified " +
            "and stop one they no longer use."
        parameters {
            organizationHeader()
        }
        responses {
            ok<PushDeviceListDto>("The caller's phones, the most recently seen first.")
            notFound(NO_ORG)
        }
    }
    delete("/api/v1/push-devices/{id}") {
        operationId = "deletePushDevice"
        tag(TAG)
        summary = "Stop push notifications to a phone."
        description = "Scope `notifications:write`. Removes one of the caller's phones in the organization " +
            "`Ostrom-Org` names, or their only one: any other id (another member's phone, another " +
            "organization's, an unknown or malformed id) is a 404."
        parameters {
            organizationHeader()
            pathId("id", "The phone's id, as `listPushDevices` answers it.")
        }
        responses {
            empty("The phone is removed.")
            notFound("Not one of the caller's phones in this organization, or no organization the caller can " +
                "act in (`not_found`).")
        }
    }
}

/** The member's own switches. */
private fun ApiCatalog.settingsOperations() {
    get("/api/v1/notifications/settings") {
        operationId = "getNotificationSettings"
        tag(TAG)
        summary = "The caller's notification switches, per channel and kind."
        description = "What the caller is told about (`notifications.v1`): `waiting` (an agent or a wait needs " +
            "them), `turn_completed`, `turn_failed` and `session_failed`, on each channel the server offers " +
            "(`push`, `slack`, later `discord`). Every kind is on until the member turns it off; `channels` " +
            "is empty while the server offers none. A member reads and changes only their own, with " +
            "$INTERACTIVE; a service key has no person to notify (403)."
        parameters {
            organizationHeader()
        }
        responses {
            ok<NotificationSettings>("The channels the server offers and one switch per channel and kind.")
            error(HttpStatusCode.Forbidden, PERSON_ONLY)
            notFound(NO_ORG)
        }
    }
    put("/api/v1/notifications/settings") {
        operationId = "setNotificationSetting"
        tag(TAG)
        summary = "Turn one kind on or off on one channel for the caller."
        description = "A channel the server does not offer, or an unknown kind, is refused (400 " +
            "`invalid_notification_setting`). Only $INTERACTIVE changes the switches (403 otherwise). " +
            "Answers every switch in force, as `GET` does."
        parameters {
            organizationHeader()
        }
        body<NotificationSettingChange>("The channel, the kind and whether the member is told.")
        responses {
            ok<NotificationSettings>("Every switch in force after the change.")
            badRequest("A channel the server does not offer, or an unknown kind " +
                "(`invalid_notification_setting`).")
            error(HttpStatusCode.Forbidden, PERSON_ONLY)
            notFound(NO_ORG)
        }
    }
}

/** The test push. */
private fun ApiCatalog.pushTestOperations() {
    post("/api/v1/notifications/push/test") {
        operationId = "sendTestPush"
        tag(TAG)
        summary = "Send a test notification to the caller's own phones."
        description = "One `test` push to each of the caller's phones in the organization, sent at once through " +
            "the provider in force, with each installation's result (`sent`, `unregistered`, `retry`, " +
            "`rejected`, and a short error code, never a provider's answer). Any member signed in " +
            "interactively (a browser session, the unlocked app or `ostrom login`), never a service key; " +
            "once every ten seconds per member on each replica (429 `push_test_rate_limited`, with " +
            "`Retry-After`). 409 `push_off` while no provider is chosen, 409 `push_no_device` when the " +
            "caller has no phone push can reach. Announced by `notifications.push.test.v1`."
        parameters {
            organizationHeader()
        }
        responses {
            ok<PushTestResults>("Whether one phone got it, and the result of each installation.")
            error(HttpStatusCode.Forbidden, PERSON_ONLY)
            notFound(NO_ORG)
            conflict("No push provider is chosen (`push_off`), or the caller has no phone push can reach " +
                "(`push_no_device`).")
            error(HttpStatusCode.TooManyRequests, "A test less than ten seconds after the caller's last one " +
                "on this replica (`push_test_rate_limited`); `Retry-After` is 10.")
        }
    }
}
