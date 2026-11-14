// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// domain/src/main/kotlin/me/nathanfallet/teamcode/domain/notifications/Push.kt; see NOTICE.
package run.ostrom.server.notifications.domain

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
     * @param credential The credential it registers with: the phone is notified while it works.
     *
     * @return The device.
     *
     * @throws InvalidPushDeviceException When it is not the FCM registration token of an iOS or Android phone with its
     *   P-256 public key (or, deprecated, an Expo push token).
     */
    suspend operator fun invoke(
        orgId: Uuid,
        memberId: Uuid,
        registration: PushRegistration,
        credential: PushCredential?,
    ): PushDevice
}

/**
 * [RegisterPushDeviceUseCase] on the repository. An `fcm` phone sends its FCM registration token and the P-256 public
 * key its pushes are encrypted for, as TeamCode's apps do (`PushRegistrationService`); an `expo` phone, deprecated, its
 * Expo push token alone.
 *
 * @param repository The phones.
 * @param encryption Checks the phone's public key.
 * @param clock The registration date.
 */
class RegisterPushDeviceUseCaseImpl(
    private val repository: PushDevicesRepository,
    private val encryption: PushEncryption,
    private val clock: Clock,
) : RegisterPushDeviceUseCase {
    override suspend fun invoke(
        orgId: Uuid,
        memberId: Uuid,
        registration: PushRegistration,
        credential: PushCredential?,
    ): PushDevice {
        val token = registration.pushToken.trim()
        val locale = registration.locale.orEmpty().trim().ifEmpty { null }
        val publicKey = registration.publicKey?.trim()?.ifEmpty { null }
        val valid = registration.platform in PLATFORMS && (locale == null || LOCALE.matches(locale)) &&
            when (registration.provider) {
                FCM -> FCM_TOKEN.matches(token) && publicKey != null && encryption.acceptsKey(publicKey)
                EXPO -> EXPO_TOKEN.matches(token) && publicKey == null
                else -> false
            }
        if (!valid) throw InvalidPushDeviceException()
        val now = clock.now()
        val device = PushDevice(
            Uuid.random(), orgId, memberId, token, registration.provider, registration.platform, locale, now, now,
            credential, publicKey,
        )
        return repository.save(device, MAX_PHONES)
    }
}

/** How many phones a member keeps in an organization: the most recently registered ones. */
const val MAX_PHONES = 10

/** The provider of a phone that registered its FCM registration token: iOS and Android alike. */
const val FCM = "fcm"

/** The provider of a phone that registered an Expo push token: deprecated, refused once the apps register FCM. */
const val EXPO = "expo"

// Top-level, not in a private companion: a companion property has a getter nothing calls, which Kover counts.
private val PLATFORMS = setOf("ios", "android")
private val EXPO_TOKEN = Regex("Expo(nent)?PushToken\\[[A-Za-z0-9_-]{8,100}]")

// TeamCode's PushPayloads.tokenPattern: what FCM hands an installation.
private val FCM_TOKEN = Regex("[A-Za-z0-9_:\\-.]{20,512}")
private val LOCALE = Regex("[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8}){0,2}")
