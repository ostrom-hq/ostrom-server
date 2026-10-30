package dev.ostrom.server.sharing.infrastructure

import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.QuotaHeaderParser
import dev.ostrom.server.sharing.domain.QuotaWindow
import kotlin.time.Instant

/**
 * Reads subscription windows from response headers, as TeamCode does: Claude's
 * `anthropic-ratelimit-unified-{5h,7d,7d_oi}-{utilization,reset,status}` (a `rejected` status is a full window), and
 * Codex's `x-codex-[<family>-]{primary,secondary}-{used-percent,window-minutes,reset-at}` classified by window length,
 * never by position.
 */
class ProviderQuotaHeaderParser : QuotaHeaderParser {
    override fun parse(provider: AccountProvider, headers: Map<String, String>, now: Instant): List<QuotaWindow> =
        when (provider) {
            AccountProvider.CLAUDE -> claude(headers, now)
            AccountProvider.CODEX -> codex(headers, now)
        }

    private fun claude(headers: Map<String, String>, now: Instant): List<QuotaWindow> =
        CLAUDE_WINDOWS.mapNotNull { (key, name) ->
            val used = if (headers["anthropic-ratelimit-unified-$key-status"] == "rejected") {
                1.0
            } else {
                headers["anthropic-ratelimit-unified-$key-utilization"]?.toDoubleOrNull()
            }
            window(name, used, epochSeconds(headers["anthropic-ratelimit-unified-$key-reset"]), now)
        }

    private fun codex(headers: Map<String, String>, now: Instant): List<QuotaWindow> =
        headers.mapNotNull { (name, value) ->
            val match = CODEX.matchEntire(name)
            val prefix = name.removeSuffix("-used-percent")
            val minutes = headers["$prefix-window-minutes"]?.toDoubleOrNull()
            val length = minutes?.let(::codexLength)
            if (match == null || length == null) return@mapNotNull null
            val family = match.groupValues[1]
            val percent = value.toDoubleOrNull()?.div(PERCENT)
            val windowName = if (family.isEmpty()) length else "model:$family:$length"
            window(windowName, percent, epochSeconds(headers["$prefix-reset-at"]), now)
        }.distinctBy { it.window }

    /** Codex names its windows by position; their length tells which is which (TeamCode's tolerance: ±10 %). */
    private fun codexLength(minutes: Double): String? = when (minutes) {
        in FIVE_HOURS_MINUTES -> "five_hour"
        in SEVEN_DAYS_MINUTES -> "seven_day"
        else -> null
    }

    private fun epochSeconds(value: String?): Instant? =
        value?.toLongOrNull()?.takeIf { it in 1..MAX_SECONDS }?.let(Instant::fromEpochSeconds)

    private fun window(name: String, used: Double?, reset: Instant?, now: Instant): QuotaWindow? {
        val fresh = reset == null || reset > now
        return used?.takeIf { fresh && it in 0.0..Double.MAX_VALUE }
            ?.let { QuotaWindow(name, it.coerceAtMost(1.0), reset, now) }
    }

    private companion object {
        val CLAUDE_WINDOWS = listOf("5h" to "five_hour", "7d" to "seven_day", "7d_oi" to "model:opus")
        val CODEX = Regex("^x-codex-(?:([a-z0-9][a-z0-9_-]{0,63})-)?(?:primary|secondary)-used-percent$")
        val FIVE_HOURS_MINUTES = 270.0..330.0
        val SEVEN_DAYS_MINUTES = 9_072.0..11_088.0
        const val PERCENT = 100
        const val MAX_SECONDS = Long.MAX_VALUE / 1000
    }
}
