// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlin.uuid.Uuid

/** The phones registered for push notifications, per organization. */
interface PushDevicesRepository {
    /**
     * @param orgId The organization.
     * @param personId The member.
     * @return Their phones.
     */
    suspend fun devices(orgId: Uuid, personId: String): List<PushDevice>

    /**
     * Registers a phone; registering its token again returns the stored device, now the given member's.
     *
     * @param device The phone.
     * @return The stored device.
     */
    suspend fun save(device: PushDevice): PushDevice

    /**
     * @param orgId The organization.
     * @param personId The member: one never removes another's phone.
     * @param id The device.
     * @return Whether it was there.
     */
    suspend fun delete(orgId: Uuid, personId: String, id: Uuid): Boolean
}

/** Sends push notifications (Expo's push service). */
interface PushSender {
    /**
     * @param messages The notifications, sent together.
     * @throws NotificationCallException When the service refuses or does not answer.
     */
    suspend fun send(messages: List<PushMessage>)
}

/** Sends emails (Resend). */
interface EmailSender {
    /** Whether an API key is configured; without one no email is sent. */
    val configured: Boolean

    /**
     * @param message The email.
     * @throws NotificationCallException When the service refuses or does not answer.
     */
    suspend fun send(message: EmailMessage)
}

/** The members' email addresses, bound by the identity module; without it, no email is sent. */
interface PersonEmails {
    /**
     * @param orgId The organization.
     * @param personId The member.
     * @return Their verified address, or null.
     */
    suspend fun emailOf(orgId: Uuid, personId: String): String?
}

/**
 * A notification service refused or did not answer: the request still waits in the console.
 *
 * @param message What failed, without any token, key or address.
 * @param cause The transport's failure, if any.
 */
class NotificationCallException(message: String, override val cause: Throwable? = null) :
    OstromException(ErrorKind.UNAVAILABLE, message)
