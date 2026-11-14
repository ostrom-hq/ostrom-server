// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.presentation

import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import run.ostrom.server.integrations.slack.domain.SlackInteractionOutcome
import run.ostrom.server.integrations.slack.domain.SlackLinkForbiddenException
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.mayWrite
import run.ostrom.server.kernel.auth.requireOrgPrincipal
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * The Slack routes: the caller's own link (`slack.v1`), a person's interactive login only (a browser session, the
 * unlocked app or `ostrom login`), and Slack's interactions at `/webhooks/slack`, authenticated by Slack's signature
 * only.
 *
 * @param dependencies The resolver and the use cases.
 */
fun Route.slackRoutes(dependencies: SlackRoutesDependencies) = with(dependencies) {
    post<SlackWebhookPath> {
        val body = call.receive<ByteArray>().decodeToString()
        val payload = runCatchingForm(body)
        val outcome = interactions(
            body,
            payload,
            call.request.header(SIGNATURE_HEADER),
            call.request.header(TIMESTAMP_HEADER),
        )
        when (outcome) {
            SlackInteractionOutcome.UNAUTHORIZED ->
                throw OstromException(ErrorKind.UNAUTHORIZED, "This request is not signed by the Slack app.")
            SlackInteractionOutcome.BAD_REQUEST ->
                throw OstromException(ErrorKind.INVALID_REQUEST, "A Slack interaction has a payload.")
            SlackInteractionOutcome.HANDLED -> call.respondText("", status = HttpStatusCode.OK)
        }
    }
    authenticated {
        get<SlackLinkPath> {
            val member = call.linkOwner(orgs)
            call.respond(get(member.orgId, member.memberId))
        }
        post<SlackLinkPath> {
            val member = call.linkOwner(orgs)
            call.respond(link(member.orgId, member.memberId))
        }
        delete<SlackLinkPath> {
            val member = call.linkOwner(orgs)
            unlink(member.orgId, member.memberId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// The credential first: one that may not learns nothing about its organization.
private suspend fun ApplicationCall.linkOwner(orgs: PrincipalOrgResolver): OrgPrincipal {
    if (!requirePrincipal().mayWrite(WriteAccess.PERSON)) throw SlackLinkForbiddenException()
    return requireOrgPrincipal(orgs, null)
}

// The form's `payload`, or null for a body that is not one (it is answered only once its signature is checked).
private fun runCatchingForm(body: String): String? = try {
    parseQueryString(body)["payload"]
} catch (_: IllegalArgumentException) {
    null
}

private const val SIGNATURE_HEADER = "X-Slack-Signature"
private const val TIMESTAMP_HEADER = "X-Slack-Request-Timestamp"
