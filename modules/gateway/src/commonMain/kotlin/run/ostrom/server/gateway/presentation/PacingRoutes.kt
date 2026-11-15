// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.presentation

import run.ostrom.server.gateway.domain.DeliveryStage
import run.ostrom.server.gateway.domain.DirectiveUseCases
import run.ostrom.server.gateway.domain.GetPacingSettingsUseCase
import run.ostrom.server.gateway.domain.PacingSettings
import run.ostrom.server.gateway.domain.SetPacingSettingsUseCase
import run.ostrom.server.gateway.domain.pacingAuditEvents
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.auth.requireRecentSignIn
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.presentation.requireCaller
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * What the capacity controller's routes need.
 *
 * @property callers Resolves the member of a credential.
 * @property getSettings Reads the controller's settings.
 * @property setSettings Changes them.
 * @property directives The advice and its delivery.
 * @property audit The organization's audit log.
 */
data class PacingRoutesDependencies(
    val callers: CallerResolver,
    val getSettings: GetPacingSettingsUseCase,
    val setSettings: SetPacingSettingsUseCase,
    val directives: DirectiveUseCases,
    val audit: OrgAuditTrail,
)

/** Who reads the settings and the preview: a person, or a credential calling models or reading guidance. */
private suspend fun ApplicationCall.reader(callers: CallerResolver, writers: WriteAccess = WriteAccess.BROWSER) =
    requireCaller(
        callers,
        RelayScopes.INFERENCE_USE,
        RelayScopes.GUIDANCE,
        RelayScopes.AGENT_SESSION,
        writers = writers,
    )

/**
 * Who reads and acknowledges the advice: a person, an agent token, or a key holding `guidance`. Its writes (claims
 * and receipts) come from every credential that reads it: they record, never decide.
 */
private suspend fun ApplicationCall.guided(callers: CallerResolver) = requireCaller(
    callers,
    RelayScopes.GUIDANCE,
    RelayScopes.AGENT_SESSION,
    writers = WriteAccess.tokens(PrincipalKind.AGENT, PrincipalKind.SERVICE, PrincipalKind.USER),
)

/**
 * The capacity controller's routes (TeamCode's `PacingSettingsRoutes` and `DirectiveRoutes`, `capacity.pacing.v1`):
 * any member reads the settings and the preview; owners and admins change the settings, signed in as a person and
 * recently; the advice is read and its delivery claimed by a person, an agent token (only for its own session) or a key
 * holding `guidance`. Advice answers are never cached.
 *
 * @param dependencies The use cases.
 */
fun Route.pacingRoutes(dependencies: PacingRoutesDependencies) = with(dependencies) {
    authenticated {
        get<PacingSettingsApi> {
            call.respond(getSettings(call.reader(callers)).toDto())
        }
        put<PacingSettingsApi> { call.putPacingSettings(dependencies) }
        get<DirectivePreviewApi> {
            call.noStore()
            val preview = directives.preview(call.reader(callers))
            call.respond(PacingPreviewDto(preview.settings.toDto(), preview.directive?.toDto()))
        }
        get<DirectiveCurrentApi> {
            call.noStore()
            call.respond(DirectiveResponseDto(directives.current(call.guided(callers), null)?.toDto()))
        }
        post<DirectiveCurrentApi> {
            call.noStore()
            val caller = call.guided(callers)
            val session = call.ownSession(call.receive<DirectiveScopeDto>().sessionId)
            call.respond(DirectiveResponseDto(directives.current(caller, session)?.toDto()))
        }
        post<DirectiveClaimApi> {
            call.noStore()
            val caller = call.guided(callers)
            val input = call.receive<DirectiveClaimRequestDto>()
            val session = call.ownSession(input.sessionId)
            val claim = directives.claim(caller, session, DeliveryStage.named(input.stage), call.tokenId())
            val answer = claim?.let { DirectiveClaimDto(it.directive.toDto(), it.claimId) }
            call.respond(answer ?: DirectiveClaimDto(null, null))
        }
        post<DirectiveReceiptApi> {
            val caller = call.guided(callers)
            val input = call.receive<DirectiveReceiptDto>()
            val session = input.sessionId?.let { call.ownSession(it) }
            val stage = DeliveryStage.named(input.stage)
            directives.acknowledge(caller, input.id, stage, session, input.claimId, call.tokenId())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun ApplicationCall.putPacingSettings(dependencies: PacingRoutesDependencies) {
    val caller = reader(dependencies.callers, WriteAccess.PERSON)
    requireRecentSignIn("change the capacity controller's settings")
    val principal = requirePrincipal()
    val input = receive<PacingSettingsDto>()
    val (before, after) = dependencies.setSettings(caller, input.observationOnly, input.autoPoolSize)
    pacingAuditEvents(before, after).forEach { event ->
        dependencies.audit.record(
            OrgAuditRecord(
                caller.orgId,
                principal,
                event,
                "settings",
                "pacing",
                auditDetails("fields" to changed(before, after)),
            ),
        )
    }
    respond(after.toDto())
}

/** The names of the fields a change set. */
private fun changed(before: PacingSettings, after: PacingSettings): List<String> = listOfNotNull(
    "observationOnly".takeIf { before.observationOnly != after.observationOnly },
    "autoPoolSize".takeIf { before.autoPoolSize != after.autoPoolSize },
)

/** An agent token names only its own session: any other is a 403. */
private fun ApplicationCall.ownSession(session: String): String {
    val principal = requirePrincipal()
    if (principal.kind == PrincipalKind.AGENT && principal.subjectId != session) throw OtherSessionException()
    return session
}

/** The credential a delivery is claimed and acknowledged by. */
private fun ApplicationCall.tokenId(): String = requirePrincipal().let(AuthenticatedPrincipal::tokenKey)

private fun AuthenticatedPrincipal.tokenKey(): String = credentialId ?: subjectId

private fun ApplicationCall.noStore() = response.header(HttpHeaders.CacheControl, "no-store")

/** An agent token naming another session than its own: a 403. */
class OtherSessionException :
    OstromException(ErrorKind.FORBIDDEN, "An agent token reads the capacity advice of its own session only.")
