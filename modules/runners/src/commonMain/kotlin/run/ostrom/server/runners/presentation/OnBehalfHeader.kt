// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import io.ktor.server.application.ApplicationCall
import run.ostrom.core.api.OnBehalfOf
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.InvalidOnBehalfException
import run.ostrom.server.runners.domain.ResolveCloudActorUseCase

/**
 * `Ostrom-On-Behalf-Of` (`runners.cloud.onbehalf.v1`, TeamCode's `onBehalfOf()`): at most one value, a member id or a
 * login; the domain decides whether the caller may use it.
 *
 * @return The member it names, or null when absent.
 *
 * @throws InvalidOnBehalfException When sent several times, or malformed.
 */
internal fun ApplicationCall.onBehalfOf(): String? = onBehalfOf(request.headers.getAll(OnBehalfOf.HEADER))

/**
 * The member the values of `Ostrom-On-Behalf-Of` name.
 *
 * @param values The header's values, or null when absent.
 *
 * @return The member, or null when absent.
 *
 * @throws InvalidOnBehalfException When there are several values, or a malformed one.
 */
internal fun onBehalfOf(values: List<String>?): String? {
    if (values == null) return null
    val value = values.singleOrNull()
    if (value == null || !OnBehalfOf.isValid(value)) throw InvalidOnBehalfException()
    return value
}

/**
 * The member a cloud session write acts for: the caller, or the one `Ostrom-On-Behalf-Of` names. The header is read
 * after the credential, so an unauthenticated or unscoped call is refused first.
 *
 * @param orgResolver The caller's organization.
 * @param resolveActor The rules of the header.
 *
 * @return The caller, acting for the effective member.
 */
internal suspend fun ApplicationCall.cloudActor(
    orgResolver: PrincipalOrgResolver,
    resolveActor: ResolveCloudActorUseCase,
): Caller {
    val principal = requirePrincipal()
    return resolveActor(orgResolver.callerOf(principal, CLOUD_WRITE), principal, onBehalfOf())
}

/**
 * The caller of a write that is the session owner's alone (GitHub access, the permission bypass): the header may only
 * name the caller.
 *
 * @param orgResolver The caller's organization.
 * @param resolveActor The rules of the header.
 * @param refusal The refusal when the header names another member.
 *
 * @return The caller.
 */
internal suspend fun ApplicationCall.cloudOwner(
    orgResolver: PrincipalOrgResolver,
    resolveActor: ResolveCloudActorUseCase,
    refusal: () -> OstromException,
): Caller {
    val principal = requirePrincipal()
    return resolveActor.owner(orgResolver.callerOf(principal, CLOUD_WRITE), principal, onBehalfOf(), refusal)
}
