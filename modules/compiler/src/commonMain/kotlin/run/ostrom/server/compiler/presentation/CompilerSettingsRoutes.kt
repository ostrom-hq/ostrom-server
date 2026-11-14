// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.presentation

import run.ostrom.server.compiler.domain.models.CompilerSettingsChange
import run.ostrom.server.compiler.domain.models.StoredCompilerSettings
import run.ostrom.server.compiler.domain.usecases.CompilerSettingsUseCaseImpl
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * How an organization compiles its packages (contract `CompilerSettings`): absent fields are the defaults.
 *
 * @property pin `auto` or `manual`.
 * @property codePublishers Who may publish code in a script; absent for every person who decides.
 * @property prodApprover The approver of a gate that names none; absent for none.
 * @property updatedAt The last save; absent while nothing is stored.
 * @property updatedBy Who saved last; absent while nothing is stored.
 */
@Serializable
data class CompilerSettingsResponse(
    val pin: String,
    val codePublishers: List<String>? = null,
    val prodApprover: String? = null,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
)

/**
 * A save of the settings (contract `SaveCompilerSettingsRequest`): it replaces every setting.
 *
 * @property pin `auto` or `manual`.
 * @property codePublishers Who may publish code in a script; absent for every person who decides.
 * @property prodApprover The approver of a gate that names none; absent for none.
 * @property updatedAt The `updatedAt` of the settings edited; absent while nothing is stored.
 */
@Serializable
data class SaveCompilerSettingsRequest(
    val pin: String,
    val codePublishers: List<String>? = null,
    val prodApprover: String? = null,
    val updatedAt: String? = null,
)

internal fun Route.compilerSettingsRoutes(dependencies: CompilerRoutesDependencies) = with(dependencies) {
    get<PackagesApi.Settings> {
        val actor = actorOf(call, CompilerScopes.readingPackages)
        call.respond(compilerSettings.read(actor.orgId).toResponse())
    }
    put<PackagesApi.Settings> {
        val actor = personOf(call, CompilerScopes.writingPackages)
        // The settings say who publishes code and who approves production: a browser session only (S2-04).
        if (!call.decides(may = actor.role in MANAGERS)) throw OstromException(ErrorKind.FORBIDDEN, CONSOLE_ONLY)
        val change = call.receive<SaveCompilerSettingsRequest>().toChange()
        val saved = compilerSettings.save(actor.orgId, actor.id, actor.role, call.requirePrincipal(), change)
        call.respond(saved.toResponse())
    }
}

internal fun StoredCompilerSettings?.toResponse(): CompilerSettingsResponse {
    val settings = this?.settings
    return CompilerSettingsResponse(
        pin = if (settings?.manualPin == true) CompilerSettingsUseCaseImpl.MANUAL else CompilerSettingsUseCaseImpl.AUTO,
        codePublishers = settings?.codePublishers,
        prodApprover = settings?.prodApprover,
        updatedAt = if (this == null) null else updatedAt.toString(),
        updatedBy = this?.updatedBy,
    )
}

internal fun SaveCompilerSettingsRequest.toChange(): CompilerSettingsChange {
    val expected = updatedAt?.let {
        Instant.parseOrNull(it) ?: throw OstromException(
            ErrorKind.INVALID_REQUEST,
            "updatedAt is a date-time.",
            type = "invalid_compiler_settings",
        )
    }
    return CompilerSettingsChange(pin, codePublishers, prodApprover, expected)
}

private val MANAGERS = setOf(OrgRole.OWNER, OrgRole.ADMIN)

private const val CONSOLE_ONLY =
    "Only an owner or an admin signed in to the console changes how the organization compiles."
