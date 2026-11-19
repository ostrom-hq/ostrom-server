// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.http

import io.ktor.http.HttpHeaders
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend

/** What an answer that says nothing of its caching carries: never stored, never rewritten. */
internal const val DEFAULT_CACHE_CONTROL = "no-store, no-transform"

/** The directive that stops a proxy from rewriting a body. */
internal const val NO_TRANSFORM = "no-transform"

/**
 * `no-transform` on every answer, as TeamCode's (6039ed5b): a proxy or a CDN such as Cloudflare rewrites a body
 * without it (e-mail address obfuscation, minification), which breaks a JSON answer or a stream. An answer that sets no
 * `Cache-Control` gets TeamCode's `no-store, no-transform`; one that sets its own (`no-cache` on a stream, an
 * artifact's `max-age`) keeps it, with `no-transform` added when it lacks it, as a second `Cache-Control` line that
 * HTTP reads as one list.
 */
internal val NoTransform = createApplicationPlugin("OstromNoTransform") {
    on(ResponseBodyReadyForSend) { call, _ ->
        val values = call.response.headers.values(HttpHeaders.CacheControl)
        when {
            values.isEmpty() -> call.response.headers.append(HttpHeaders.CacheControl, DEFAULT_CACHE_CONTROL)
            values.none { NO_TRANSFORM in it } -> call.response.headers.append(HttpHeaders.CacheControl, NO_TRANSFORM)
        }
    }
}
