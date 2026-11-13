// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What a person is told on their phone (`GET`/`PUT /api/v1/me/notifications`). They are the person's own, across
 * their organizations: an account setting, like the profile.
 *
 * @property requests A request for their yes.
 * @property decisions An answer to a request they made.
 * @property spending A budget about to be reached.
 * @property quietHours Hours without notifications, if any.
 */
data class NotificationPreferences(
    val requests: Boolean,
    val decisions: Boolean,
    val spending: Boolean,
    val quietHours: QuietHours? = null,
)

/**
 * Hours without notifications, in the person's time zone; [end] before [start] spans midnight.
 *
 * @property start `HH:MM`, 24-hour.
 * @property end `HH:MM`, 24-hour.
 * @property timeZone An IANA time zone (`Europe/Paris`) or `UTC`.
 */
data class QuietHours(val start: String, val end: String, val timeZone: String)

/** The preferences of a person who never set any: told of requests and answers, not of spending. */
val DEFAULT_NOTIFICATION_PREFERENCES = NotificationPreferences(requests = true, decisions = true, spending = false)

private val CLOCK_TIME = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")

/** An IANA name (`Area/Location`, up to three parts) or `UTC`; the phone, not the server, applies it. */
private val TIME_ZONE = Regex("[A-Za-z][A-Za-z0-9_+-]{0,31}(/[A-Za-z0-9_+-]{1,32}){0,2}")

/** The people's notification preferences. */
interface NotificationPreferencesRepository {
    /**
     * Reads a person's preferences.
     *
     * @param userId The person.
     *
     * @return Their preferences, or null when they never set any.
     */
    suspend fun get(userId: Uuid): NotificationPreferences?

    /**
     * Stores a person's preferences, replacing the previous ones.
     *
     * @param userId The person.
     * @param preferences The preferences.
     * @param at When.
     */
    suspend fun put(userId: Uuid, preferences: NotificationPreferences, at: Instant)
}

/** Reads the caller's notification preferences. */
interface GetNotificationPreferencesUseCase {
    /**
     * Reads them.
     *
     * @param userId The caller.
     *
     * @return Their preferences, or [DEFAULT_NOTIFICATION_PREFERENCES].
     */
    suspend operator fun invoke(userId: Uuid): NotificationPreferences
}

/**
 * [GetNotificationPreferencesUseCase].
 *
 * @param preferences The stored preferences.
 */
class GetNotificationPreferencesUseCaseImpl(
    private val preferences: NotificationPreferencesRepository,
) : GetNotificationPreferencesUseCase {
    override suspend fun invoke(userId: Uuid): NotificationPreferences =
        preferences.get(userId) ?: DEFAULT_NOTIFICATION_PREFERENCES
}

/** Sets the caller's notification preferences. */
interface SetNotificationPreferencesUseCase {
    /**
     * Checks and stores them.
     *
     * @param userId The caller.
     * @param preferences The new preferences.
     *
     * @return The stored preferences.
     *
     * @throws InvalidQuietHoursException When the quiet hours are not two different `HH:MM` and a time zone name.
     */
    suspend operator fun invoke(userId: Uuid, preferences: NotificationPreferences): NotificationPreferences
}

/**
 * [SetNotificationPreferencesUseCase].
 *
 * @param preferences The stored preferences.
 * @param clock The clock.
 */
class SetNotificationPreferencesUseCaseImpl(
    private val preferences: NotificationPreferencesRepository,
    private val clock: Clock,
) : SetNotificationPreferencesUseCase {
    override suspend fun invoke(userId: Uuid, preferences: NotificationPreferences): NotificationPreferences {
        if (preferences.quietHours?.isValid() == false) throw InvalidQuietHoursException()
        this.preferences.put(userId, preferences, clock.nowMillis())
        return preferences
    }

    private fun QuietHours.isValid(): Boolean = CLOCK_TIME.matches(start) && CLOCK_TIME.matches(end) &&
        start != end && TIME_ZONE.matches(timeZone)
}

/** The quiet hours are not two different `HH:MM` times and a time zone name. */
class InvalidQuietHoursException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "Quiet hours need a start and an end (HH:MM, different) and a time zone such as Europe/Paris.",
)
