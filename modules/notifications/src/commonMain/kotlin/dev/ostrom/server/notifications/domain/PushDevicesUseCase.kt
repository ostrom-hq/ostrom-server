// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.domain

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** A member registers and removes their own phones. */
interface PushDevicesUseCase {
    /**
     * Registers the caller's phone.
     *
     * @param actor A person of the organization.
     * @param registration The phone: token, provider, platform, locale.
     * @return The device.
     * @throws OstromException 403 for anything but a person, 400 for a malformed registration.
     */
    suspend fun register(actor: GateActor, registration: PushRegistration): PushDevice

    /**
     * Removes one of the caller's phones.
     *
     * @param actor A person of the organization.
     * @param id The device.
     * @throws OstromException 403 for anything but a person, 404 when it is not theirs.
     */
    suspend fun remove(actor: GateActor, id: Uuid)
}

/**
 * A phone, as the app registers it.
 *
 * @property pushToken The Expo push token (`ExponentPushToken[…]`).
 * @property provider `expo`.
 * @property platform `ios` or `android`.
 * @property locale The phone's language.
 */
data class PushRegistration(val pushToken: String, val provider: String, val platform: String, val locale: String?)

/**
 * [PushDevicesUseCase] on the repository.
 *
 * @param repository The phones.
 * @param clock The registration date.
 */
class PushDevicesUseCaseImpl(
    private val repository: PushDevicesRepository,
    private val clock: Clock,
) : PushDevicesUseCase {
    override suspend fun register(actor: GateActor, registration: PushRegistration): PushDevice {
        val person = personOf(actor)
        val token = registration.pushToken.trim()
        val locale = registration.locale.orEmpty().trim().ifEmpty { null }
        val valid = TOKEN.matches(token) && registration.provider == PROVIDER &&
            registration.platform in PLATFORMS && (locale == null || LOCALE.matches(locale))
        if (!valid) throw OstromException(ErrorKind.INVALID_REQUEST, "A phone registers an Expo push token.")
        val device = PushDevice(
            Uuid.random(),
            actor.orgId,
            person,
            token,
            PROVIDER,
            registration.platform,
            locale,
            clock.now(),
        )
        return repository.save(device)
    }

    override suspend fun remove(actor: GateActor, id: Uuid) {
        if (!repository.delete(actor.orgId, personOf(actor), id)) {
            throw OstromException(ErrorKind.NOT_FOUND, "This phone is not registered.")
        }
    }

    // A phone is a person's: an agent, a device token or a service never registers one.
    private fun personOf(actor: GateActor): String = actor.personId?.takeIf { actor.kind == ActorKind.USER }
        ?: throw OstromException(ErrorKind.FORBIDDEN, "Only a person registers a phone for notifications.")

    private companion object {
        const val PROVIDER = "expo"
    }
}

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val PLATFORMS = setOf("ios", "android")
private val TOKEN = Regex("Expo(nent)?PushToken\\[[A-Za-z0-9_-]{8,100}]")
private val LOCALE = Regex("[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8}){0,2}")
