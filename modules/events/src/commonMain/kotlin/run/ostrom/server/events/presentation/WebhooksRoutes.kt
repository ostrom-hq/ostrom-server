// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.presentation

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import run.ostrom.core.api.models.CreateWebhookRequest
import run.ostrom.core.api.models.UpdateWebhookRequest
import run.ostrom.core.api.models.WebhookDeliveryList
import run.ostrom.core.api.models.WebhookList
import run.ostrom.server.events.domain.WebhookNotFoundException
import run.ostrom.server.events.domain.WebhookRules
import run.ostrom.server.events.domain.WebhookTestRateException
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import kotlin.uuid.Uuid

/**
 * The organization's outgoing webhooks (`webhooks.v1`): owners and admins only, see [webhookActorOf].
 *
 * @param dependencies The resolver and the service.
 */
fun Route.webhooksRoutes(dependencies: WebhooksRoutesDependencies) = with(dependencies) {
    authenticated {
        get<WebhooksApi> {
            val actor = orgs.webhookActorOf(call.requirePrincipal(), write = false)
            call.respond(WebhookList(webhooks.list(actor).map { it.toResponse() }))
        }
        post<WebhooksApi> {
            val actor = orgs.webhookActorOf(call.requirePrincipal(), write = true)
            val request = call.receive<CreateWebhookRequest>()
            val created = webhooks.create(actor, request.url, request.types, request.description.orEmpty())
            call.respond(HttpStatusCode.Created, created.toResponse())
        }
        get<WebhooksApi.Id> { params ->
            val actor = orgs.webhookActorOf(call.requirePrincipal(), write = false)
            call.respond(webhooks.get(actor, webhookId(params.id)).toResponse())
        }
        put<WebhooksApi.Id> { params ->
            val actor = orgs.webhookActorOf(call.requirePrincipal(), write = true)
            val request = call.receive<UpdateWebhookRequest>()
            val changed = webhooks.update(
                actor,
                webhookId(params.id),
                request.version,
                request.url,
                request.types,
                request.description.orEmpty(),
                request.enabled,
            )
            call.respond(changed.toResponse())
        }
        delete<WebhooksApi.Id> { params ->
            val actor = orgs.webhookActorOf(call.requirePrincipal(), write = true)
            webhooks.delete(actor, webhookId(params.id))
            call.respond(HttpStatusCode.NoContent)
        }
        actionRoutes(dependencies)
    }
}

/** The log, the test and the rotation. */
private fun Route.actionRoutes(dependencies: WebhooksRoutesDependencies) = with(dependencies) {
    get<WebhooksApi.Id.Deliveries> { params ->
        val actor = orgs.webhookActorOf(call.requirePrincipal(), write = false)
        val limit = params.limit ?: WebhookRules.DEFAULT_LOG_PAGE
        val log = webhooks.deliveries(actor, webhookId(params.parent.id), limit)
        call.respond(WebhookDeliveryList(log.map { it.toResponse() }))
    }
    post<WebhooksApi.Id.Test> { params ->
        val actor = orgs.webhookActorOf(call.requirePrincipal(), write = true)
        val queued = try {
            webhooks.test(actor, webhookId(params.parent.id))
        } catch (limited: WebhookTestRateException) {
            call.response.header(HttpHeaders.RetryAfter, limited.retryAfterSeconds.toString())
            throw limited
        }
        call.respond(HttpStatusCode.Accepted, queued.toResponse())
    }
    post<WebhooksApi.Id.RotateSecret> { params ->
        val actor = orgs.webhookActorOf(call.requirePrincipal(), write = true)
        call.respond(webhooks.rotateSecret(actor, webhookId(params.parent.id)).toResponse())
    }
}

/** A webhook id; one that cannot exist is a webhook that does not. */
private fun webhookId(raw: String): Uuid = Uuid.parseOrNull(raw) ?: throw WebhookNotFoundException()
