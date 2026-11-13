// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.presentation

import run.ostrom.server.identity.domain.AnswerDeviceAuthorizationUseCase
import run.ostrom.server.identity.domain.CreatePersonalTokenUseCase
import run.ostrom.server.identity.domain.EndWebSessionUseCase
import run.ostrom.server.identity.domain.GetDeviceAuthorizationUseCase
import run.ostrom.server.identity.domain.GetProfileUseCase
import run.ostrom.server.identity.domain.ListAuditEventsUseCase
import run.ostrom.server.identity.domain.ListTokensUseCase
import run.ostrom.server.identity.domain.PollDeviceAuthorizationUseCase
import run.ostrom.server.identity.domain.RevokeTokenUseCase
import run.ostrom.server.identity.domain.RotateTokenUseCase
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.identity.domain.StartDeviceAuthorizationUseCase
import run.ostrom.server.identity.domain.WebSessionsOfUserUseCase
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.SignOutListener
import run.ostrom.server.kernel.settings.InstanceAdministrators
import run.ostrom.server.kernel.settings.NoInstanceAdministrators

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
 * @property webSessions Lists and ends the person's browser sessions.
 * @property signOutListeners Told after a sign-out (the phones registered with the credential are dropped).
 * @property orgResolver The organization `/me` shows when the request names one in `Ostrom-Org`.
 * @property instanceAdmins Whether `/me`'s person administers the instance (the admin module's).
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
    val webSessions: WebSessionsOfUserUseCase,
    val signOutListeners: List<SignOutListener>,
    val orgResolver: PrincipalOrgResolver,
    val instanceAdmins: InstanceAdministrators = NoInstanceAdministrators(),
)
