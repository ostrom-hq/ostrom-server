// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.presentation

import dev.ostrom.server.automations.domain.AutomationQuery
import dev.ostrom.server.automations.domain.AutomationRules
import dev.ostrom.server.automations.domain.InvalidAutomationException
import dev.ostrom.server.automations.domain.InvalidWebhookPayloadException
import dev.ostrom.server.automations.domain.ManageAutomationsUseCase
import dev.ostrom.server.automations.domain.RunAutomationsUseCase
import dev.ostrom.server.automations.domain.WebhookDelivery
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
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
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlin.uuid.Uuid

/**
 * What the automation routes need.
 *
 * @property manage Creates, reads, changes and deletes.
 * @property runs Triggers, tests and lists runs.
 * @property orgResolver The member a request acts for.
 * @property publicUrl The server's public URL, the base of webhook URLs (relative ones when empty).
 */
data class AutomationsRoutesDependencies(
    val manage: ManageAutomationsUseCase,
    val runs: RunAutomationsUseCase,
    val orgResolver: PrincipalOrgResolver,
    val publicUrl: String,
)

/**
 * The automations API under `/api/v1/automations`, and its signed webhook `POST /api/v1/automations/{id}/trigger`,
 * which takes no login (the signature is the proof).
 *
 * @param dependencies The use cases.
 */
fun Route.automationsRoutes(dependencies: AutomationsRoutesDependencies) = with(dependencies) {
    post<AutomationsApi.Id.Trigger> { params ->
        val delivery = WebhookDelivery(
            timestamp = call.request.header(TIMESTAMP),
            signature = call.request.header(SIGNATURE),
            deliveryKey = call.request.header(IDEMPOTENCY_KEY) ?: call.request.header(DELIVERY),
        )
        // One byte more than allowed is enough to refuse a body that is too large without reading it all; it is
        // refused before the signature, which a cut body could never match.
        val body = call.receiveChannel().readRemaining(MAX_BODY + 1L).readByteArray()
        if (body.size > MAX_BODY) throw InvalidWebhookPayloadException("The body is at most 64 KiB.")
        val triggered = runs.trigger(automationId(params.parent.id), delivery, body)
        if (triggered.deduplicated) call.response.header(IDEMPOTENCY_REPLAYED, "true")
        val run = triggered.run
        val response = TriggerResponse(run.id.toString(), run.status.wire, triggered.deduplicated)
        call.respond(HttpStatusCode.Accepted, response)
    }
    authenticated {
        definitionRoutes(dependencies)
        stateRoutes(dependencies)
        runRoutes(dependencies)
    }
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
        val until = rotated.automation.previousSecretValidUntil?.toString()
        call.respond(SecretResponse(rotated.secret.orEmpty(), until))
    }
}

/** Tests and the history. */
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
