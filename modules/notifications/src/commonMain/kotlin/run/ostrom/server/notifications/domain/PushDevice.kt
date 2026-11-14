// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A phone a member registered for push notifications in their organization: one per push token, so a phone signed in
 * again by another member of the organization is theirs from then on.
 *
 * @property id The device.
 * @property orgId The organization.
 * @property memberId The member the phone notifies.
 * @property pushToken The FCM registration token, never answered back nor logged.
 * @property provider `fcm` (`expo` for a phone an older app registered: never notified).
 * @property platform `ios` or `android`.
 * @property locale The phone's language, if it gave one.
 * @property createdAt When the phone was first registered.
 * @property seenAt When the phone last registered (the app registers again in a new language, or an hour later).
 * @property credential Who registered it, with which credential: null for a phone registered before the server
 *   kept it, which is notified again once the app registers it anew.
 * @property publicKey The phone's P-256 public key (SubjectPublicKeyInfo, DER, base64) every `fcm` push is encrypted
 *   for; null for an older `expo` phone.
 */
data class PushDevice(
    val id: Uuid,
    val orgId: Uuid,
    val memberId: Uuid,
    val pushToken: String,
    val provider: String,
    val platform: String,
    val locale: String?,
    val createdAt: Instant,
    val seenAt: Instant,
    val credential: PushCredential? = null,
    val publicKey: String? = null,
)

/**
 * The credential a phone was registered with: the phone is notified while it works, and forgotten when the person
 * signs out with it (`POST /auth/logout`).
 *
 * @property userId The person.
 * @property credentialId The phone app's token or the browser session.
 * @property browser Whether it is a browser session, which `POST /auth/logout/everywhere` ends with the others.
 */
data class PushCredential(val userId: Uuid, val credentialId: Uuid, val browser: Boolean)

/**
 * A phone, as the app registers it (`registerPushDevice`).
 *
 * @property pushToken The FCM registration token.
 * @property provider `fcm`.
 * @property platform `ios` or `android`.
 * @property locale The phone's language, if any.
 * @property publicKey The phone's P-256 public key, required with `fcm`.
 */
data class PushRegistration(
    val pushToken: String,
    val provider: String,
    val platform: String,
    val locale: String?,
    val publicKey: String? = null,
)
