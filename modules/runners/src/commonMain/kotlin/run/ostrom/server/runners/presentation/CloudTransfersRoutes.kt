// SPDX-License-Identifier: Apache-2.0
@file:Suppress("MatchingDeclarationName") // The routes are the file's subject; the dependencies are their input.

package run.ostrom.server.runners.presentation

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.contentType
import io.ktor.http.ContentType
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.http.bodyLimit
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudTransfer
import run.ostrom.server.runners.domain.CloudTransfersUseCase
import run.ostrom.server.runners.domain.IdempotencyKeyRequiredException
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.InvalidTransferException
import run.ostrom.server.runners.domain.ResolveCloudActorUseCase
import run.ostrom.server.runners.domain.ResolveCloudActorUseCaseImpl
import run.ostrom.server.runners.domain.TransferDirection
import run.ostrom.server.runners.domain.TransferForbiddenException
import run.ostrom.server.runners.domain.TransferIdempotency
import run.ostrom.server.runners.domain.TransferListing
import run.ostrom.server.runners.domain.TransferNotFoundException
import run.ostrom.server.runners.domain.TransferRequest
import run.ostrom.server.runners.domain.TransferState
import kotlin.uuid.Uuid

/**
 * What the transfer routes need.
 *
 * @property transfers The use cases.
 * @property orgResolver The member a request acts for.
 * @property resolveActor The rules of `Ostrom-On-Behalf-Of` on the writes (`runners.cloud.onbehalf.writes.v1`).
 */
data class CloudTransfersRoutesDependencies(
    val transfers: CloudTransfersUseCase,
    val orgResolver: PrincipalOrgResolver,
    val resolveActor: ResolveCloudActorUseCase = ResolveCloudActorUseCaseImpl(null),
)

/**
 * The ownership transfer routes (`runners.cloud.transfer.v1`): asking, listing, reading, accepting, declining and
 * cancelling. Writes are for a person signed in: an agent or a service key never gives a session away. An
 * administrator may act for the member `Ostrom-On-Behalf-Of` names (`runners.cloud.onbehalf.writes.v1`, TeamCode's
 * `actor()`): the transfer is checked against that member, and records the administrator as who asked or decided.
 *
 * @param dependencies The use cases.
 */
fun Route.cloudTransfersRoutes(dependencies: CloudTransfersRoutesDependencies) = with(dependencies) {
    authenticated {
        bodyLimit(BODY_LIMIT) {
            post<CloudSessionTransferApi> { params -> offer(call, params) }
            post<CloudTransfersApi.Id.Accept> { params ->
                val (caller, principal) = call.transferActor(dependencies)
                val resume = call.optionalBody<AcceptCloudTransferRequest>()?.resume
                val accepted = transfers.accept(caller, principal, transferId(params.parent.id), resume)
                call.respond(HttpStatusCode.Accepted, accepted.toResponse())
            }
            post<CloudTransfersApi.Id.Decline> { params ->
                val (caller, principal) = call.transferActor(dependencies)
                call.respond(transfers.decline(caller, principal, transferId(params.parent.id)).toResponse())
            }
            post<CloudTransfersApi.Id.Cancel> { params ->
                val (caller, principal) = call.transferActor(dependencies)
                call.respond(transfers.cancel(caller, principal, transferId(params.parent.id)).toResponse())
            }
        }
        get<CloudTransferRecipientsApi> {
            val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_READ)
            call.respond(
                CloudTransferRecipientsResponse(
                    transfers.recipients(caller).map {
                        CloudTransferRecipientResponse(it.id, it.login, it.administrator)
                    },
                ),
            )
        }
        get<CloudTransfersApi> { params ->
            val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_READ)
            val limit = params.limit ?: DEFAULT_LIMIT
            val listing = call.transferListing(params, limit)
            val page = transfers.list(caller, listing)
            call.respond(
                CloudTransferPageResponse(
                    page.map { it.toResponse() },
                    page.lastOrNull()?.let { last -> last.id.toString().takeIf { page.size == limit } },
                ),
            )
        }
        get<CloudTransfersApi.Id> { params ->
            val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_READ)
            call.respond(transfers.get(caller, transferId(params.id)).toResponse())
        }
    }
}

/**
 * The caller of a transfer write, a person, acting for the member `Ostrom-On-Behalf-Of` names when they administer
 * the organization, and the credential they presented. Writes of a transfer are a person's: an agent or a service key
 * acts for nobody in person, and is refused before the header is read.
 */
private suspend fun ApplicationCall.transferActor(
    dependencies: CloudTransfersRoutesDependencies,
): Pair<Caller, AuthenticatedPrincipal> {
    val principal = requirePrincipal()
    if (principal.kind != PrincipalKind.USER) {
        throw TransferForbiddenException("Only a person signed in transfers sessions, not an agent or a service key.")
    }
    return cloudActor(dependencies.orgResolver, dependencies.resolveActor) to principal
}

private fun ApplicationCall.idempotencyKey(): String {
    val key = request.header(IDEMPOTENCY_KEY)?.trim()
    if (key == null || !KEY.matches(key)) throw IdempotencyKeyRequiredException()
    return key
}

/** The accept body is optional: a bare POST keeps what the offer asked for. */
private suspend inline fun <reified T : Any> ApplicationCall.optionalBody(): T? =
    if (request.contentType().match(ContentType.Application.Json)) receive<T>() else null

private fun requireMemberReference(reference: String) {
    if (!MEMBER.matches(reference)) throw InvalidTransferException("Name the recipient by member id or login.")
}

private fun transferId(raw: String): Uuid = Uuid.parseOrNull(raw) ?: throw TransferNotFoundException()

private suspend fun CloudTransfersRoutesDependencies.offer(call: ApplicationCall, params: CloudSessionTransferApi) {
    val (caller, principal) = call.transferActor(this)
    val key = call.idempotencyKey()
    val request = call.receive<CreateCloudTransferRequest>()
    val sessionId = Uuid.parseOrNull(params.id) ?: throw TransferNotFoundException()
    // The digest covers the session, the body and the caller, like a creation's.
    val digest = "POST /cloud/sessions/$sessionId/transfer|${request.toMemberId}|${request.resume}|" +
        caller.memberId
    val result = transfers.request(
        caller,
        principal,
        sessionId,
        TransferRequest(request.toMemberId.also(::requireMemberReference), request.resume),
        TransferIdempotency(key, digest),
    )
    if (result.replayed) call.response.header(IDEMPOTENCY_REPLAYED, "true")
    call.respond(HttpStatusCode.Accepted, result.transfer.toResponse())
}

private fun ApplicationCall.transferListing(params: CloudTransfersApi, limit: Int) = TransferListing(
    direction = direction(params.direction),
    states = request.queryParameters.getAll("state").orEmpty().map {
        TransferState.fromWire(it) ?: throw InvalidCloudSessionRequestException("Unknown transfer state.")
    }.toSet(),
    sessionId = params.session?.let {
        Uuid.parseOrNull(it) ?: throw InvalidCloudSessionRequestException("Unknown session.")
    },
    before = params.before?.let {
        Uuid.parseOrNull(it) ?: throw InvalidCloudSessionRequestException("Malformed cursor.")
    },
    limit = limit,
)

private fun direction(raw: String?): TransferDirection = when (raw) {
    null, "all" -> TransferDirection.BOTH
    "incoming" -> TransferDirection.INCOMING
    "outgoing" -> TransferDirection.OUTGOING
    else -> throw InvalidCloudSessionRequestException("Use direction incoming, outgoing or all.")
}

/** Maps a transfer to the API. */
fun CloudTransfer.toResponse() = CloudTransferResponse(
    id = id.toString(),
    sessionId = sessionId.toString(),
    fromMemberId = fromMemberId,
    toMemberId = toMemberId,
    state = state.wire,
    initiatedBy = initiatedBy,
    decidedBy = decidedBy,
    resume = resume,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    expiresAt = expiresAt?.toString(),
    decidedAt = decidedAt?.toString(),
    completedAt = completedAt?.toString(),
    atEpoch = atEpoch,
    failure = failure?.let { SessionFailureDto(it.code, it.message) },
)

private const val BODY_LIMIT = 1024L
private const val DEFAULT_LIMIT = 50
private const val IDEMPOTENCY_KEY = "Idempotency-Key"
private const val IDEMPOTENCY_REPLAYED = "Idempotency-Replayed"
private val KEY = Regex("[A-Za-z0-9._:-]{1,128}")
private val MEMBER = Regex("[A-Za-z0-9._@-]{1,100}")
