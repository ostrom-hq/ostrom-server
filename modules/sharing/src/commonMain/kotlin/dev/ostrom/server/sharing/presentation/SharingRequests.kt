// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.presentation

import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.sharing.domain.InvalidAccountException
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToLong

/** The body as [serializer] reads it; malformed, a 400 that never quotes it. */
internal fun <T> JsonObject.decoded(serializer: KSerializer<T>): T = try {
    OstromJson.decodeFromJsonElement(serializer, this)
} catch (error: IllegalArgumentException) {
    // SerializationException is an IllegalArgumentException: a missing field, a wrong type.
    throw BadRequestException("Malformed body.", error)
}

/** USD millionths of [usd], more than 0 and at most [MAX_DAILY_USD] (0 would close the account for good). */
internal fun usdToMicros(usd: Double): Long {
    if (!(usd > 0.0 && usd <= MAX_DAILY_USD)) {
        throw InvalidAccountException("dailyAllocationUsd is more than 0, at most ${MAX_DAILY_USD.toLong()}.")
    }
    return (usd * MICROS_PER_USD).roundToLong()
}

private const val MAX_DAILY_USD = 1_000_000.0

/** USD millionths in a dollar. */
internal const val MICROS_PER_USD = 1_000_000.0
