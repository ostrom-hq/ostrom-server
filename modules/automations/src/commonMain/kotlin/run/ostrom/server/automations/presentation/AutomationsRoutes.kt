// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.presentation

import run.ostrom.server.automations.domain.AutomationQuery
import run.ostrom.server.automations.domain.AutomationRunNotFoundException
import run.ostrom.server.automations.domain.AutomationRules
import run.ostrom.server.automations.domain.InvalidAutomationException
import run.ostrom.server.automations.domain.InvalidSignatureException
import run.ostrom.server.automations.domain.InvalidWebhookPayloadException
import run.ostrom.server.automations.domain.LATEST_VERSION
import run.ostrom.server.automations.domain.RunAutomationsUseCase
import run.ostrom.server.automations.domain.WebhookDelivery
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlin.uuid.Uuid

/**
 * The automations API under `/api/v1/automations`, and its signed webhook `POST /api/v1/automations/{id}/trigger`
 * (also pinned to a version: `…/v/{version}/trigger`), which takes no login (the signature is the proof).
 *
 * @param dependencies The use cases.
 */
fun Route.automationsRoutes(dependencies: AutomationsRoutesDependencies) = with(dependencies) {
    post<AutomationsApi.Id.Trigger> { params -> webhook(runs, params.parent.id, LATEST_VERSION) }
    post<AutomationsApi.Id.VersionTrigger> { params -> webhook(runs, params.parent.id, params.version) }
    authenticated {
        definitionRoutes(dependencies)
        stateRoutes(dependencies)
        runRoutes(dependencies)
    }
}

/** A signed webhook request, on `…/trigger` or pinned to a version of the definition (`…/v/{version}/trigger`). */
private suspend fun RoutingContext.webhook(runs: RunAutomationsUseCase, rawId: String, version: String) {
    val delivery = WebhookDelivery(
        timestamp = call.request.header(TIMESTAMP),
        signature = call.request.header(SIGNATURE),
        deliveryKey = call.request.header(IDEMPOTENCY_KEY) ?: call.request.header(DELIVERY),
    )
    // One byte more than allowed is enough to refuse a body that is too large without reading it all; it is
    // refused before the signature, which a cut body could never match.
    val body = call.receiveChannel().readRemaining(MAX_BODY + 1L).readByteArray()
    if (body.size > MAX_BODY) throw InvalidWebhookPayloadException("The body is at most 64 KiB.")
    // An id that cannot exist answers like a wrong signature: the webhook never says which automations exist.
    val id = Uuid.parseOrNull(rawId) ?: throw InvalidSignatureException()
    val triggered = runs.trigger(id, delivery, body, version)
    val run = triggered.run
    val response = TriggerResponse(run.id.toString(), run.status.wire, triggered.deduplicated)
    call.respond(HttpStatusCode.Accepted, response)
}

/** Listing, creating, reading, changing and deleting. */
private fun Route.definitionRoutes(dependencies: AutomationsRoutesDependencies) = with(dependencies) {
    get<AutomationsApi> { params ->
        val caller = orgResolver.automationCallerOf(call.requirePrincipal(), AUTOMATIONS_READ)
        val page = manage.list(caller, AutomationQuery(params.owner, cursor(params.before), params.limit))
        call.respond(AutomationsResponse(page.items.map { it.toResponse(publicUrl) }, page.next?.toString()))
    }
    post<AutomationsApi> {
        val caller = orgResolver.automationCallerOf(call.requirePrincipal(), AUTOMATIONS_WRITE)
        val created = manage.create(caller, call.receive<AutomationRequest>().toDefinition())
        call.respond(HttpStatusCode.Created, created.automation.toResponse(publicUrl, created.secret))
    }
    get<AutomationsApi.Id> { params ->
        val caller = orgResolver.automationCallerOf(call.requirePrincipal(), AUTOMATIONS_READ)
        call.respond(manage.get(caller, automationId(params.id)).toResponse(publicUrl))
    }
    put<AutomationsApi.Id> { params ->
        val caller = orgResolver.automationCallerOf(call.requirePrincipal(), AUTOMATIONS_WRITE)
        val request = call.receive<AutomationRequest>()
        val version = request.version ?: throw InvalidAutomationException("A change gives the version it read.")
        val updated = manage.update(caller, automationId(params.id), version, request.toDefinition())
        call.respond(updated.toResponse(publicUrl))
    }
    delete<AutomationsApi.Id> { params ->
        val caller = orgResolver.automationCallerOf(call.requirePrincipal(), AUTOMATIONS_WRITE)
        manage.delete(caller, automationId(params.id))
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Arming, pausing and rotating the secret. */
private fun Route.stateRoutes(dependencies: AutomationsRoutesDependencies) = with(dependencies) {
    post<AutomationsApi.Id.Enable> { params ->
        val caller = orgResolver.automationCallerOf(call.requirePrincipal(), AUTOMATIONS_WRITE)
        call.respond(manage.setEnabled(caller, automationId(params.parent.id), true).toResponse(publicUrl))
    }
    post<AutomationsApi.Id.Disable> { params ->
        val caller = orgResolver.automationCallerOf(call.requirePrincipal(), AUTOMATIONS_WRITE)
        call.respond(manage.setEnabled(caller, automationId(params.parent.id), false).toResponse(publicUrl))
    }
    post<AutomationsApi.Id.RotateSecret> { params ->
        val caller = orgResolver.automationCallerOf(call.requirePrincipal(), AUTOMATIONS_WRITE)
        val rotated = manage.rotateSecret(caller, automationId(params.parent.id))
        call.respond(SecretResponse(rotated.secret, rotated.previousValidUntil.toString()))
    }
}

/** Tests, cancelling and the history. */
private fun Route.runRoutes(dependencies: AutomationsRoutesDependencies) = with(dependencies) {
    post<AutomationsApi.Id.Run> { params ->
        val caller = orgResolver.automationCallerOf(call.requirePrincipal(), AUTOMATIONS_WRITE)
        val request = call.receive<TestRunRequest>()
        val id = automationId(params.parent.id)
        if (request.dryRun) {
            call.respond(runs.dryRun(caller, id, request.payload).toResponse())
        } else {
            val tested = runs.test(caller, id, request.payload, call.request.header(IDEMPOTENCY_KEY))
            if (tested.deduplicated) call.response.header(IDEMPOTENCY_REPLAYED, "true")
            call.respond(HttpStatusCode.Accepted, tested.run.toResponse())
        }
    }
    post<AutomationsApi.Id.CancelRun> { params ->
        val principal = call.requirePrincipal()
        val caller = orgResolver.automationCallerOf(principal, AUTOMATIONS_WRITE)
        val runId = Uuid.parseOrNull(params.runId) ?: throw AutomationRunNotFoundException()
        call.respond(runs.cancel(principal, caller, automationId(params.parent.id), runId).toResponse())
    }
    get<AutomationsApi.Id.Runs> { params ->
        val caller = orgResolver.automationCallerOf(call.requirePrincipal(), AUTOMATIONS_READ)
        val page = runs.runs(caller, automationId(params.parent.id), cursor(params.before), params.limit)
        call.respond(RunsResponse(page.items.map { it.toResponse() }, page.next?.toString()))
    }
}

private fun cursor(raw: String?): Uuid? =
    raw?.let { Uuid.parseOrNull(it) ?: throw InvalidAutomationException("before is an id of the listing.") }

private const val MAX_BODY = AutomationRules.MAX_PAYLOAD_BYTES
private const val TIMESTAMP = "Ostrom-Timestamp"
private const val SIGNATURE = "Ostrom-Signature"
private const val DELIVERY = "Ostrom-Delivery"
private const val IDEMPOTENCY_KEY = "Idempotency-Key"
private const val IDEMPOTENCY_REPLAYED = "Idempotency-Replayed"
