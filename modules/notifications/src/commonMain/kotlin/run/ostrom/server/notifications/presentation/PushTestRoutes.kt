// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.presentation

import io.ktor.http.HttpHeaders
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import run.ostrom.core.api.models.PushTestResult
import run.ostrom.core.api.models.PushTestResults
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requireOrgPrincipal
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.notifications.domain.NotificationSettingsForbiddenException
import run.ostrom.server.notifications.domain.PushResult
import run.ostrom.server.notifications.domain.PushTestRateLimitedException
import run.ostrom.server.notifications.domain.SendTestPushUseCase
import run.ostrom.server.notifications.domain.TEST_WINDOW

/**
 * "Send a test to my phone" (`notifications.push.test.v1`): any member signed in interactively, to their own phones
 * in the organization they act in; never a service key, an agent's or a device's token.
 *
 * @param dependencies The resolver and the test.
 */
fun Route.pushTestRoutes(dependencies: PushTestRoutesDependencies) = with(dependencies) {
    authenticated {
        post<PushTestApi> {
            if (!call.requirePrincipal().isInteractivePerson()) throw NotificationSettingsForbiddenException()
            val member = call.requireOrgPrincipal(orgs, null)
            val results = try {
                sendTest(member.orgId, member.memberId)
            } catch (limited: PushTestRateLimitedException) {
                call.response.header(HttpHeaders.RetryAfter, TEST_WINDOW.inWholeSeconds.toString())
                throw limited
            }
            call.respond(results.toDto())
        }
    }
}

/**
 * @return The contract's results: `ok` when one phone got it.
 */
internal fun List<PushResult>.toDto(): PushTestResults = PushTestResults(
    ok = any { it.status == PushResult.Status.SENT },
    results = map { PushTestResult(it.status.name.lowercase(), it.error) },
)
