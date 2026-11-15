// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage.presentation

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.auth.requireRecentSignIn
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.ScopeRequiredException
import run.ostrom.server.secrets.presentation.requireCaller
import run.ostrom.server.usage.domain.InvalidUsageReconciliationException
import run.ostrom.server.usage.domain.PendingUsage
import run.ostrom.server.usage.domain.ReconciledUsage
import run.ostrom.server.usage.domain.UsageEvidence
import run.ostrom.server.usage.domain.UsageReconciliationChange
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The usage review (TeamCode's `usageReconciliationRoutes`, `capacity.usagereconciliation.v1`), inside an
 * authenticated route: owners and admins, a person (a service key is refused even with the scope, and so are agent
 * and device tokens); a correction from the console needs a sign-in less than five minutes old and is written to the
 * organization's audit log (`usage.reconciled`). Nothing is cached.
 *
 * @param dependencies The use cases.
 */
fun Route.usageReconciliationRoutes(dependencies: UsageRoutesDependencies) = with(dependencies) {
    get<PendingUsageApi> { resource ->
        val caller = call.requirePerson(callers, UsageScopes.READ)
        val named = pending(caller, resource.member, resource.before)
        val page = named.value
        call.noStore()
        call.respond(PendingUsagePage(page.items.map { it.toDto(named.logins) }, page.next?.toString()))
    }
    get<UsageReconciliationsApi> { resource ->
        val caller = call.requirePerson(callers, UsageScopes.READ)
        val named = reconciliations(caller, resource.member, resource.before)
        val page = named.value
        call.noStore()
        call.respond(UsageReconciliationsPage(page.items.map { it.toDto(named.logins) }, page.next?.toString()))
    }
    get<UsageReservationApi> { resource ->
        val caller = call.requirePerson(callers, UsageScopes.READ)
        val named = review(caller, resource.id)
        call.noStore()
        call.respond(
            UsageReviewDto(
                named.value.pending?.toDto(named.logins),
                named.value.reconciliation?.toDto(named.logins),
            ),
        )
    }
    post<UsageReservationApi.Reconcile> { resource ->
        val caller = call.requirePerson(callers, UsageScopes.WRITE)
        // A member is refused before being asked to sign in again.
        caller.requireAdmin()
        call.requireRecentSignIn("correct an unknown charge")
        val principal = call.requirePrincipal()
        val request = call.receive<ReconcileUsageRequest>()
        val source = UsageEvidence.entries.find { it.name == request.source }
            ?: throw InvalidUsageReconciliationException("Choose request-specific provider evidence.")
        val change = UsageReconciliationChange(
            request.expectedMicros,
            request.micros,
            source,
            request.reference,
            request.reason,
            request.expectedProviderRequestId,
        )
        val named = reconcile(caller, resource.parent.id, change)
        val record = named.value.reconciliation
        if (named.value.changed) {
            val details = auditDetails("previous_micros" to record.previousMicros, "micros" to record.micros)
            val subject = record.id.toString()
            audit.record(OrgAuditRecord(caller.orgId, principal, EVENT, "usage_reservation", subject, details))
        }
        call.respond(record.toDto(named.logins))
    }
}

/** A person's call: a service key is refused even when it holds the scope ([requireCaller] refuses agents, devices). */
private suspend fun ApplicationCall.requirePerson(callers: CallerResolver, scope: String): Caller {
    if (requirePrincipal().kind == PrincipalKind.SERVICE) throw ScopeRequiredException("a person's sign-in")
    return requireCaller(callers, scope)
}

/** The audit event of a correction. */
private const val EVENT = "usage.reconciled"

/** The review shows what the ledger holds now: never cached. */
private fun ApplicationCall.noStore() = response.header(HttpHeaders.CacheControl, "no-store")

private fun Map<Uuid, String>.nameOf(id: Uuid): String = this[id] ?: id.toString()

private fun PendingUsage.toDto(logins: Map<Uuid, String>) = PendingUsageDto(
    id = id.toString(),
    member = logins.nameOf(memberId),
    micros = micros,
    heldAt = heldAt.toString(),
    leaseExpiredAt = leaseExpiredAt.toString(),
    workflowId = workflowId,
    launchId = launchId,
    providerRequestId = providerRequestId,
)

private fun ReconciledUsage.toDto(logins: Map<Uuid, String>) = UsageReconciliationDto(
    id = id.toString(),
    member = logins.nameOf(memberId),
    workflowId = workflowId,
    launchId = launchId,
    providerRequestId = providerRequestId,
    heldAt = heldAt.toString(),
    previousMicros = previousMicros,
    micros = micros,
    source = source.name,
    reference = reference,
    reason = reason,
    reconciledBy = logins.nameOf(reconciledBy),
    reconciledAt = reconciledAt.toString(),
)
