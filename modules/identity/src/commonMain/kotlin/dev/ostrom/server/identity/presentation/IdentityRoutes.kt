package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.AnswerDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.AuditEvent
import dev.ostrom.server.identity.domain.CreatePersonalTokenUseCase
import dev.ostrom.server.identity.domain.DEVICE_POLL_INTERVAL
import dev.ostrom.server.identity.domain.DevicePollResult
import dev.ostrom.server.identity.domain.EndWebSessionUseCase
import dev.ostrom.server.identity.domain.GetDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.GetProfileUseCase
import dev.ostrom.server.identity.domain.IssuedToken
import dev.ostrom.server.identity.domain.ListAuditEventsUseCase
import dev.ostrom.server.identity.domain.ListTokensUseCase
import dev.ostrom.server.identity.domain.PollDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.Profile
import dev.ostrom.server.identity.domain.RevokeTokenUseCase
import dev.ostrom.server.identity.domain.RotateTokenUseCase
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.SessionRequiredException
import dev.ostrom.server.identity.domain.StartDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.TokenNotFoundException
import dev.ostrom.server.identity.domain.isBrowserSession
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.auth.requireScope
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * What the identity routes need.
 *
 * @property config The public URLs and cookie settings.
 * @property hasher Derives the CSRF token of the calling session.
 * @property getProfile Reads the user.
 * @property endWebSession Signs out.
 * @property listAuditEvents Reads the account-level audit log.
 * @property createPersonalToken Creates a token.
 * @property listTokens Lists tokens.
 * @property revokeToken Revokes a token.
 * @property rotateToken Rotates a token.
 * @property startDeviceAuthorization Starts an `ostrom login`.
 * @property pollDeviceAuthorization Polls it.
 * @property getDeviceAuthorization Shows it to the user.
 * @property answerDeviceAuthorization Approves or denies it.
 */
data class IdentityRoutesDependencies(
    val config: IdentityConfig,
    val hasher: SecretHasher,
    val getProfile: GetProfileUseCase,
    val endWebSession: EndWebSessionUseCase,
    val listAuditEvents: ListAuditEventsUseCase,
    val createPersonalToken: CreatePersonalTokenUseCase,
    val listTokens: ListTokensUseCase,
    val revokeToken: RevokeTokenUseCase,
    val rotateToken: RotateTokenUseCase,
    val startDeviceAuthorization: StartDeviceAuthorizationUseCase,
    val pollDeviceAuthorization: PollDeviceAuthorizationUseCase,
    val getDeviceAuthorization: GetDeviceAuthorizationUseCase,
    val answerDeviceAuthorization: AnswerDeviceAuthorizationUseCase,
)

/**
 * The identity routes.
 *
 * @param dependencies The use cases.
 */
fun Route.identityRoutes(dependencies: IdentityRoutesDependencies) = with(dependencies) {
    post<AuthApi.Device.Code, DeviceCodeRequest> { _, request ->
        val started = startDeviceAuthorization(request.clientName, call.request.origin.remoteAddress)
        val authorization = started.authorization
        val page = config.consoleUrl + "/device"
        call.respond(
            DeviceCodeResponse(
                deviceCode = started.deviceCode,
                userCode = authorization.userCode,
                verificationUri = page,
                verificationUriComplete = page + "?code=" + authorization.userCode,
                expiresIn = (authorization.expiresAt - authorization.createdAt).inWholeSeconds,
                interval = DEVICE_POLL_INTERVAL.inWholeSeconds,
            ),
        )
    }
    post<AuthApi.Device.Token, DeviceTokenRequest> { _, request ->
        call.respond(pollDeviceAuthorization(request.deviceCode).toDeviceTokenResponse())
    }

    authenticated {
        get<MeApi> {
            val principal = call.requireScope("profile:read")
            val csrf = if (principal.isBrowserSession) call.request.cookies[SESSION_COOKIE]?.let { hasher.csrfToken(it) } else null
            call.respond(getProfile(principal.userId()).toProfileResponse(csrf))
        }
        get<MeApi.Audit> { params ->
            val principal = call.requireScope("profile:read")
            val events = listAuditEvents(null, principal.userId(), params.before, params.limit)
            call.respond(events.toAuditEventsResponse(params.limit))
        }
        post<AuthApi.Logout> {
            val principal = call.requirePrincipal()
            val userId = principal.userId()
            val credential = principal.credentialId?.let(::parseId) ?: throw SessionRequiredException()
            if (principal.isBrowserSession) {
                endWebSession(credential)
                call.clearSessionCookie(config)
            } else {
                revokeToken(userId, credential)
            }
            call.respond(HttpStatusCode.NoContent)
        }
        post<AuthApi.Logout.Everywhere> {
            val principal = call.requireBrowserSession()
            endWebSession.everywhere(principal.userId())
            call.clearSessionCookie(config)
            call.respond(HttpStatusCode.NoContent)
        }

        get<TokensApi> {
            val principal = call.requireBrowserSession()
            call.respond(TokensResponse(listTokens(principal.userId()).map { it.toTokenResponse() }))
        }
        post<TokensApi, CreateTokenRequest> { _, request ->
            val principal = call.requireBrowserSession()
            val issued = createPersonalToken(principal.userId(), request.name, request.scopes, request.expiresInDays?.days)
            call.respond(HttpStatusCode.Created, issued.toCreatedTokenResponse())
        }
        delete<TokensApi.Id> { params ->
            val principal = call.requireBrowserSession()
            revokeToken(principal.userId(), parseId(params.id) ?: throw TokenNotFoundException())
            call.respond(HttpStatusCode.NoContent)
        }
        post<TokensApi.Id.Rotate> { params ->
            val principal = call.requireBrowserSession()
            val issued = rotateToken(principal.userId(), parseId(params.parent.id) ?: throw TokenNotFoundException())
            call.respond(HttpStatusCode.Created, issued.toCreatedTokenResponse())
        }

        get<AuthApi.Device> { params ->
            val principal = call.requireBrowserSession()
            val code = params.userCode ?: throw OstromException(ErrorKind.INVALID_REQUEST, "userCode is required.")
            val authorization = getDeviceAuthorization(principal.userId(), code)
            call.respond(
                DeviceAuthorizationResponse(authorization.userCode, authorization.clientName, authorization.expiresAt.toString()),
            )
        }
        post<AuthApi.Device.Answer, AnswerDeviceRequest> { _, request ->
            val principal = call.requireBrowserSession()
            answerDeviceAuthorization(principal.userId(), request.userCode, request.approve)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Sets the session cookie after a sign-in: HttpOnly (no script reads it), Secure when the API is served over HTTPS,
 * SameSite=Lax (sent on the console's own requests and top-level navigations, not on another site's forms), for
 * the whole API, until the session expires.
 *
 * @param started The session.
 * @param config Where the API is served.
 */
fun ApplicationCall.setSessionCookie(started: StartedSession, config: IdentityConfig) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE,
            value = started.secret,
            maxAge = (started.session.expiresAt - started.session.createdAt).inWholeSeconds.toInt(),
            path = "/",
            secure = config.secureCookies,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

/**
 * Removes the session cookie (sign-out).
 *
 * @param config Where the API is served.
 */
fun ApplicationCall.clearSessionCookie(config: IdentityConfig) {
    response.cookies.append(
        Cookie(
            name = SESSION_COOKIE,
            value = "",
            maxAge = 0,
            path = "/",
            secure = config.secureCookies,
            httpOnly = true,
            extensions = mapOf("SameSite" to "Lax"),
        ),
    )
}

/**
 * The principal, refused unless it is a user's browser session: managing tokens and approving a CLI login are
 * never done with a token, so a leaked token cannot mint others.
 *
 * @return The principal.
 *
 * @throws SessionRequiredException Otherwise.
 */
fun ApplicationCall.requireBrowserSession(): AuthenticatedPrincipal {
    val principal = requirePrincipal()
    if (!principal.isBrowserSession) throw SessionRequiredException()
    return principal
}

/**
 * The user id of a user principal.
 *
 * @return The id.
 *
 * @throws SessionRequiredException When the principal is not a user (an agent).
 */
fun AuthenticatedPrincipal.userId(): Uuid {
    if (kind != PrincipalKind.USER) throw SessionRequiredException()
    return parseId(subjectId) ?: throw SessionRequiredException()
}

private fun parseId(raw: String): Uuid? = Uuid.parseOrNull(raw)

/** Maps the profile; [csrfToken] only for a browser session. */
fun Profile.toProfileResponse(csrfToken: String?) = ProfileResponse(
    id = user.id.toString(),
    displayName = user.displayName,
    primaryEmail = user.primaryEmail,
    avatarUrl = user.avatarUrl,
    createdAt = user.createdAt.toString(),
    emails = emails.map { EmailResponse(it.email, it.verified) },
    providers = issuers,
    csrfToken = csrfToken,
)

/** Maps a token, without its secret. */
fun ApiToken.toTokenResponse() = TokenResponse(
    id = id.toString(),
    name = name,
    kind = kind.name.lowercase(),
    scopes = scopes.sorted(),
    hint = hint,
    createdAt = createdAt.toString(),
    expiresAt = expiresAt?.toString(),
    lastUsedAt = lastUsedAt?.toString(),
)

/** Maps an issued token, with its secret. */
fun IssuedToken.toCreatedTokenResponse() = CreatedTokenResponse(token.toTokenResponse(), secret)

/** Maps a poll result. */
fun DevicePollResult.toDeviceTokenResponse(): DeviceTokenResponse {
    val interval = DEVICE_POLL_INTERVAL.inWholeSeconds
    return when (this) {
        DevicePollResult.Pending -> DeviceTokenResponse("pending", interval = interval)
        DevicePollResult.SlowDown -> DeviceTokenResponse("slow_down", interval = interval * 2)
        DevicePollResult.Denied -> DeviceTokenResponse("denied", interval = interval)
        DevicePollResult.Expired -> DeviceTokenResponse("expired", interval = interval)
        is DevicePollResult.Approved -> DeviceTokenResponse(
            status = "approved",
            accessToken = token.secret,
            expiresAt = token.token.expiresAt?.toString(),
            scopes = token.token.scopes.sorted(),
            interval = interval,
        )
    }
}

/** Maps an audit event. */
fun AuditEvent.toAuditEventResponse() = AuditEventResponse(
    id = id.toString(),
    sequence = sequence,
    orgId = entry.orgId?.toString(),
    actorId = entry.actorId?.toString(),
    action = entry.action,
    targetType = entry.targetType,
    targetId = entry.targetId,
    details = entry.details,
    occurredAt = occurredAt.toString(),
    previousHash = previousHash,
    hash = hash,
    signature = signature,
    keyId = keyId,
)

/**
 * Maps a page of audit events.
 *
 * @param limit The requested page size: a full page may have a next one.
 */
fun List<AuditEvent>.toAuditEventsResponse(limit: Int) = AuditEventsResponse(
    events = map { it.toAuditEventResponse() },
    nextBefore = if (size >= limit.coerceIn(1, 200)) lastOrNull()?.sequence else null,
)
