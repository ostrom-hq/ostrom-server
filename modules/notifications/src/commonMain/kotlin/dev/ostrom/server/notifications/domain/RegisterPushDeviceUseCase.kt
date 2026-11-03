// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.domain

import kotlin.time.Clock
import kotlin.uuid.Uuid

/** A member registers the phone they are signed in on, so the server can tell them a request waits for them. */
interface RegisterPushDeviceUseCase {
    /**
     * Registers the phone.
     *
     * @param orgId The organization the member acts in.
     * @param memberId The member.
     * @param registration The phone.
     *
     * @return The device.
     *
     * @throws InvalidPushDeviceException When it is not an Expo push token of an iOS or Android phone.
     */
    suspend operator fun invoke(orgId: Uuid, memberId: Uuid, registration: PushRegistration): PushDevice
}

/**
 * [RegisterPushDeviceUseCase] on the repository.
 *
 * @param repository The phones.
 * @param clock The registration date.
 */
class RegisterPushDeviceUseCaseImpl(
    private val repository: PushDevicesRepository,
    private val clock: Clock,
) : RegisterPushDeviceUseCase {
    override suspend fun invoke(orgId: Uuid, memberId: Uuid, registration: PushRegistration): PushDevice {
        val token = registration.pushToken.trim()
        val locale = registration.locale.orEmpty().trim().ifEmpty { null }
        val valid = PUSH_TOKEN.matches(token) && registration.provider == EXPO &&
            registration.platform in PLATFORMS && (locale == null || LOCALE.matches(locale))
        if (!valid) throw InvalidPushDeviceException()
        val now = clock.now()
        val device = PushDevice(Uuid.random(), orgId, memberId, token, EXPO, registration.platform, locale, now, now)
        return repository.save(device, MAX_PHONES)
    }
}

/** How many phones a member keeps in an organization: the most recently registered ones. */
const val MAX_PHONES = 10

private const val EXPO = "expo"

// Top-level, not in a private companion: a companion property has a getter nothing calls, which Kover counts.
private val PLATFORMS = setOf("ios", "android")
private val PUSH_TOKEN = Regex("Expo(nent)?PushToken\\[[A-Za-z0-9_-]{8,100}]")
private val LOCALE = Regex("[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8}){0,2}")
