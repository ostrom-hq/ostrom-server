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
    override fun parse(provider: AccountProvider, headers: Map<String, String>, now: Instant): List<QuotaWindow> = when (provider) {
        AccountProvider.CLAUDE -> claude(headers, now)
        AccountProvider.CODEX -> codex(headers, now)
    }

    private fun claude(headers: Map<String, String>, now: Instant): List<QuotaWindow> =
        listOf("5h" to "five_hour", "7d" to "seven_day", "7d_oi" to "model:opus").mapNotNull { (key, name) ->
            val used = if (headers["anthropic-ratelimit-unified-$key-status"] == "rejected") {
                1.0
            } else {
                headers["anthropic-ratelimit-unified-$key-utilization"]?.toDoubleOrNull()
            }
            val reset = headers["anthropic-ratelimit-unified-$key-reset"]?.toLongOrNull()?.takeIf { it in 1..MAX_SECONDS }?.let(Instant::fromEpochSeconds)
            window(name, used, reset, now)
        }

    private fun codex(headers: Map<String, String>, now: Instant): List<QuotaWindow> = headers.keys.mapNotNull { name ->
        val match = CODEX.matchEntire(name) ?: return@mapNotNull null
        val family = match.groupValues[1].takeIf { it.isNotEmpty() }
        val prefix = name.removeSuffix("-used-percent")
        val seconds = headers["$prefix-window-minutes"]?.toDoubleOrNull()?.times(60) ?: return@mapNotNull null
        val length = when (seconds) {
            in 16_200.0..19_800.0 -> "five_hour"
            in 544_320.0..665_280.0 -> "seven_day"
            else -> return@mapNotNull null
        }
        val percent = headers[name]?.toDoubleOrNull()?.div(100)
        val reset = headers["$prefix-reset-at"]?.toLongOrNull()?.takeIf { it in 1..MAX_SECONDS }?.let(Instant::fromEpochSeconds)
        window(if (family == null) length else "model:$family:$length", percent, reset, now)
    }.distinctBy { it.window }

    private fun window(name: String, used: Double?, reset: Instant?, now: Instant): QuotaWindow? {
        if (used == null || !used.isFinite() || used < 0 || (reset != null && reset <= now)) return null
        return QuotaWindow(name, used.coerceAtMost(1.0), reset, now)
    }

    private companion object {
        val CODEX = Regex("^x-codex-(?:([a-z0-9][a-z0-9_-]{0,63})-)?(?:primary|secondary)-used-percent$")
        const val MAX_SECONDS = Long.MAX_VALUE / 1000
    }
}
