// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.presentation

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import run.ostrom.core.api.models.DiscordChannels
import run.ostrom.core.api.models.DiscordLinkStart
import run.ostrom.server.integrations.discord.domain.DiscordInteractionOutcome
import run.ostrom.server.integrations.discord.domain.DiscordLinkForbiddenException
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.BrowserSessionRequiredException
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TOKEN_AUTH
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.mayWrite
import run.ostrom.server.kernel.auth.requireOrgPrincipal
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * The Discord routes: the caller's own link (`discord.v1`), a person's interactive login only (a browser session to
 * start one, since Discord's sign-in happens in that browser), Discord's return at the public callback, which always
 * sends the browser back to the console's notifications, and Discord's interactions at `/webhooks/discord`,
 * authenticated by Discord's signature only.
 *
 * @param dependencies The resolver and the use cases.
 */
fun Route.discordRoutes(dependencies: DiscordRoutesDependencies) = with(dependencies) {
    post<DiscordWebhookPath> {
        // One byte more than allowed is enough to refuse a body that is too large without reading it all, before
        // its signature: a cut body could never match it.
        val bytes = call.receiveChannel().readRemaining(MAX_BODY + 1L).readByteArray()
        if (bytes.size > MAX_BODY) throw OstromException(ErrorKind.INVALID_REQUEST, "The body is at most 64 KiB.")
        val outcome = interactions(bytes, call.request.header(SIGNATURE_HEADER), call.request.header(TIMESTAMP_HEADER))
        when (outcome) {
            DiscordInteractionOutcome.Unauthorized ->
                throw OstromException(ErrorKind.UNAUTHORIZED, "This request is not signed by the Discord application.")
            DiscordInteractionOutcome.BadRequest ->
                throw OstromException(ErrorKind.INVALID_REQUEST, "Not an interaction this server answers.")
            is DiscordInteractionOutcome.Respond ->
                call.respondText(outcome.response.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
    // Discord sends the browser back with its session cookie (SameSite=Lax): the session is read when there is one.
    authenticate(TOKEN_AUTH, optional = true) {
        get<DiscordLinkPath.Callback> { params ->
            val session = call.principal<AuthenticatedPrincipal>()?.let { sessionOf(it) }
            val outcome = callbackOutcome { complete(params.state, params.code, params.error, session) }
            val page = "$consoleUrl${DiscordChannels.RETURN_PAGE}"
            call.respondRedirect("$page?${DiscordChannels.RETURN_PARAMETER}=${outcome.encodeURLParameter()}")
        }
    }
    authenticated {
        get<DiscordLinkPath> {
            val member = call.linkOwner(orgs)
            call.respond(get(member.orgId, member.memberId))
        }
        post<DiscordLinkPath> {
            val principal = call.requirePrincipal()
            if (!principal.mayWrite(WriteAccess.PERSON)) throw DiscordLinkForbiddenException()
            if (principal.channel != PrincipalChannel.BROWSER) throw BrowserSessionRequiredException()
            val member = call.requireOrgPrincipal(orgs, null)
            call.respond(DiscordLinkStart(start(member.orgId, member.memberId, sessionOf(principal))))
        }
        delete<DiscordLinkPath> {
            val member = call.linkOwner(orgs)
            unlink(member.orgId, member.memberId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// The credential first: one that may not learns nothing about its organization.
private suspend fun ApplicationCall.linkOwner(orgs: PrincipalOrgResolver): OrgPrincipal {
    if (!requirePrincipal().mayWrite(WriteAccess.PERSON)) throw DiscordLinkForbiddenException()
    return requireOrgPrincipal(orgs, null)
}

/**
 * The callback's outcome, for the console: `linked`, a refusal's `error.type`, or `discord_unreachable` for any other
 * failure, so that the browser is always sent back.
 *
 * @param complete Finishes the link.
 * @return The value of `discord=`.
 */
internal suspend fun callbackOutcome(complete: suspend () -> Unit): String = try {
    complete()
    DiscordChannels.LINKED
} catch (refused: OstromException) {
    refused.type
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    DiscordChannels.UNREACHABLE
}

/** The browser session a link is bound to: its credential, else its person. */
private fun sessionOf(principal: AuthenticatedPrincipal): String = principal.credentialId ?: principal.subjectId

// A Discord interaction is a few kilobytes: its message carries our own short text.
private const val MAX_BODY = 64 * 1024
private const val SIGNATURE_HEADER = "X-Signature-Ed25519"
private const val TIMESTAMP_HEADER = "X-Signature-Timestamp"
