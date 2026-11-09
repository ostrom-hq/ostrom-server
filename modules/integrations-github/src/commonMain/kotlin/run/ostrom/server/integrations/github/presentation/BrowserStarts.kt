// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.presentation

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * Whether a browser navigation to a flow's start came from a third-party site (AUDIT-SECU-2 B1): the starts are GET
 * links of the console, so a page elsewhere could open one in a signed-in owner's browser. A browser says where a
 * request comes from in `Sec-Fetch-Site`; a `cross-site` one is accepted only from the console's own pages (its
 * `Referer`, which a cross-origin navigation still carries as an origin). Without the header (an older browser, a
 * client that is not a browser), the start is accepted: its state stays bound to the session that asked.
 *
 * @param site The `Sec-Fetch-Site` header.
 * @param referer The `Referer` header.
 * @param webOrigin The console's origin.
 * @return True when a third-party site started it.
 */
internal fun startedElsewhere(site: String?, referer: String?, webOrigin: String): Boolean {
    val fromConsole = referer != null && (referer == webOrigin || referer.startsWith("$webOrigin/"))
    return site == CROSS_SITE && !fromConsole
}

/**
 * Refuses a start a third-party site triggered: 403.
 *
 * @param webOrigin The console's origin.
 * @throws OstromException 403 when [startedElsewhere].
 */
internal fun ApplicationCall.requireStartedHere(webOrigin: String) {
    if (startedElsewhere(request.header(SEC_FETCH_SITE), request.header(HttpHeaders.Referrer), webOrigin)) {
        throw OstromException(ErrorKind.FORBIDDEN, "Start this from the Ostrom console.")
    }
}

/**
 * The session of a browser, or the credential of a token: what finishes a flow must be what started it.
 *
 * @param principal The caller.
 * @return Its session.
 */
internal fun sessionOf(principal: AuthenticatedPrincipal): String = principal.credentialId ?: principal.subjectId

private const val SEC_FETCH_SITE = "Sec-Fetch-Site"
private const val CROSS_SITE = "cross-site"
