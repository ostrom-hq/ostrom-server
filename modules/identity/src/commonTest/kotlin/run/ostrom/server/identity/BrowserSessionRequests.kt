// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity

import run.ostrom.server.kernel.auth.CSRF_COOKIE
import run.ostrom.server.kernel.auth.CSRF_HEADER
import run.ostrom.server.kernel.auth.SESSION_COOKIE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

/** The console's origin in tests: the default `OSTROM_CONSOLE_URL`, the only allowed origin when none is listed. */
const val TEST_CONSOLE_ORIGIN = "http://localhost:5173"

/**
 * What the console sends on a request (audit S2-08): the session cookie, and for a state-changing one the readable
 * CSRF cookie, the same value in `X-CSRF-Token`, and its `Origin`.
 *
 * @param secret The session cookie's value.
 * @param csrfToken The session's CSRF token, or null for a read.
 */
fun HttpRequestBuilder.browserSession(secret: String, csrfToken: String?) {
    if (csrfToken == null) {
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=$secret")
    } else {
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=$secret; $CSRF_COOKIE=$csrfToken")
        header(CSRF_HEADER, csrfToken)
        header(HttpHeaders.Origin, TEST_CONSOLE_ORIGIN)
    }
}
