// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.domain

import kotlin.uuid.Uuid

/**
 * What one phone is asked to show.
 *
 * @property to The phone's push token: sent to the push service only, never logged.
 * @property title The title.
 * @property body The text.
 * @property data What the app opens: `{kind: "approval", approvalId}` (`registerPushDevice` in the contract).
 */
data class PushMessage(val to: String, val title: String, val body: String, val data: Map<String, String>)

/** Sends push notifications (Expo's push service, which hands them to APNs and FCM). */
interface PushSender {
    /**
     * Sends the notifications of one approver, together.
     *
     * @param messages One per phone.
     *
     * @throws PushNotSentException When the service refuses them or does not answer.
     */
    suspend fun send(messages: List<PushMessage>)
}

/**
 * The push service refused or did not answer: the request still waits in the console and the app's queue, and the
 * notification is tried again (NotificationOutbox).
 *
 * @param reason What failed, naming the service and its status only: never a token.
 */
class PushNotSentException(reason: String) : Exception(reason)

/**
 * An approver, as the notifications reach them in one organization.
 *
 * @property userId The person.
 * @property memberId Their membership: the phones they registered in the organization.
 * @property email Their primary address when they proved they own it, else null: no e-mail then.
 * @property pushes Whether they want a push for a request (`requests` of `PUT /me/notifications`).
 */
data class Approver(val userId: Uuid, val memberId: Uuid, val email: String?, val pushes: Boolean)

/** Finds the approvers a gate names (their logins) among the members of its organization. */
interface Approvers {
    /**
     * @param orgId The gate's organization.
     * @param login The approver's login, any case.
     *
     * @return The approver, or null when nobody has that login or they are not a member of the organization.
     */
    suspend fun reach(orgId: Uuid, login: String): Approver?
}

/** Whether the credential a phone was registered with still works: a revoked or expired one notifies nobody. */
interface LiveCredentials {
    /**
     * @param userId The person.
     * @param credential The credential the phone was registered with.
     *
     * @return Whether it is still live.
     */
    suspend fun live(userId: Uuid, credential: PushCredential): Boolean
}

/** The organizations a person belongs to: where their phones may be registered. */
fun interface PersonOrganizations {
    /**
     * @param userId The person.
     *
     * @return Their organizations.
     */
    suspend fun of(userId: Uuid): List<Uuid>
}
