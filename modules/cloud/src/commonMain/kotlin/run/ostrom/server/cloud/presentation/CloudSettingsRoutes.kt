// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.presentation

import run.ostrom.server.cloud.domain.CloudSettings
import run.ostrom.server.cloud.domain.InvalidCloudSettingsException
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.auth.requireWrite
import run.ostrom.server.secrets.presentation.requireCaller
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.time.Instant

/** The organization's cloud settings: read by its people, saved by an owner or an admin signed in. */
internal fun Route.cloudSettingsRoutes(dependencies: CloudAdminRoutesDependencies) = with(dependencies) {
    get<CloudSettingsApi> {
        val caller = call.requireCaller(callers, CLOUD_READ)
        call.requirePrincipal().requireWrite(WriteAccess.PERSON)
        call.respond(settings.get(caller).toResponse())
    }
    put<CloudSettingsApi> {
        val caller = call.requireCaller(callers, CLOUD_WRITE)
        call.requirePrincipal().requireWrite(WriteAccess.PERSON)
        caller.requireAdmin()
        val request = call.receive<SaveCloudSettingsRequest>()
        val expected = request.updatedAt?.let {
            Instant.parseOrNull(it) ?: throw InvalidCloudSettingsException("updatedAt is a date-time.")
        }
        val saved = settings.save(caller, CloudSettings(request.backgroundWorkMaxMinutes), expected)
        audit.record(
            OrgAuditRecord(
                caller.orgId,
                call.requirePrincipal(),
                "cloud.settings.saved",
                "cloud_settings",
                caller.orgId.toString(),
                mapOf("backgroundWorkMaxMinutes" to saved.backgroundWorkMaxMinutes.toString()),
            ),
        )
        call.respond(saved.toResponse())
    }
}

internal fun CloudSettings.toResponse() = CloudSettingsResponse(
    backgroundWorkMaxMinutes = backgroundWorkMaxMinutes,
    updatedAt = updatedAt?.toString(),
    updatedBy = updatedBy?.toString(),
)
