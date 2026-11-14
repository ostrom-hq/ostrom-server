// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.presentation

import io.ktor.server.application.ApplicationCall
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import run.ostrom.core.api.models.NotificationSettingChange
import run.ostrom.core.api.models.NotificationSettings
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.mayWrite
import run.ostrom.server.kernel.auth.requireOrgPrincipal
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.notifications.domain.MemberNotificationSettings
import run.ostrom.server.notifications.domain.NotificationSettingsForbiddenException
import run.ostrom.core.api.models.NotificationSetting as NotificationSettingDto

/**
 * A member's own notification switches (`notifications.v1`), in the organization they act in (`Ostrom-Org`, or their
 * only one): a person's interactive login only (a browser session, the unlocked app or `ostrom login`), as TeamCode's
 * `requireInteractive`. A personal token made in the console, an agent's, a device's or a service key never: they
 * have no person to notify, or must not silence one.
 *
 * @param dependencies The resolver and the service.
 */
fun Route.notificationSettingsRoutes(dependencies: NotificationSettingsRoutesDependencies) = with(dependencies) {
    authenticated {
        get<NotificationSettingsApi> {
            val member = call.settingsOwner(orgs)
            call.respond(settings.settings(member.orgId, member.memberId).toDto())
        }
        put<NotificationSettingsApi, NotificationSettingChange> { _, body ->
            val member = call.settingsOwner(orgs)
            val changed = settings.set(member.orgId, member.memberId, body.channel, body.kind, body.enabled)
            call.respond(changed.toDto())
        }
    }
}

/**
 * Whether a credential is a person's interactive login, before its organization is known.
 *
 * @return True when it is.
 */
internal fun AuthenticatedPrincipal.isInteractivePerson(): Boolean = mayWrite(WriteAccess.PERSON)

// The credential first: one that may not learns nothing about its organization.
private suspend fun ApplicationCall.settingsOwner(orgs: PrincipalOrgResolver): OrgPrincipal {
    if (!requirePrincipal().isInteractivePerson()) throw NotificationSettingsForbiddenException()
    return requireOrgPrincipal(orgs, null)
}

/**
 * @return The contract's settings.
 */
internal fun MemberNotificationSettings.toDto(): NotificationSettings = NotificationSettings(
    channels = channels,
    settings = settings.map { NotificationSettingDto(it.channel, it.kind.wire, it.enabled) },
)
