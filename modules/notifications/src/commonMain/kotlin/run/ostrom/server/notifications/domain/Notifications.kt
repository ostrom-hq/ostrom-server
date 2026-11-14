// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import kotlin.uuid.Uuid

/**
 * What one phone is asked to show.
 *
 * @property device The phone: its push token goes to the push provider only, never logged.
 * @property title The title.
 * @property body The text.
 * @property data What the app opens: `{kind: "approval", approvalId}` (`registerPushDevice` in the contract).
 */
data class PushMessage(val device: PushDevice, val title: String, val body: String, val data: Map<String, String>) {
    /** The phone's push token. */
    val to: String get() = device.pushToken
}

/** Sends push notifications to the phones, through their provider. */
interface PushSender {
    /**
     * Sends the notifications of one approver, together.
     *
     * @param messages One per phone.
     *
     * @throws PushNotSentException When the service refuses them for now or does not answer: tried again.
     * @throws PushRejectedException When the service refuses them for good: given up at once.
     */
    suspend fun send(messages: List<PushMessage>)
}

/**
 * The push service refused or did not answer: the request still waits in the console and the app's queue, and the
 * notification is tried again (NotificationOutbox).
 *
 * @param reason What failed, naming the service and its status only: never a token.
 * @property forGood Whether another attempt would only be refused again ([PushRejectedException]).
 */
open class PushNotSentException(reason: String, val forGood: Boolean = false) : Exception(reason)

/**
 * The push service refused the notification for good (its credentials, the message): it is not tried again.
 *
 * @param reason What failed, a short code of ours (`fcm_403`): never a token nor the provider's answer.
 */
class PushRejectedException(reason: String) : PushNotSentException(reason, forGood = true)

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
