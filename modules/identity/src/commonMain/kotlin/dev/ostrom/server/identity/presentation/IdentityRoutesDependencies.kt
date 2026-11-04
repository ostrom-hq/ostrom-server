// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.AnswerDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.CreatePersonalTokenUseCase
import dev.ostrom.server.identity.domain.EndWebSessionUseCase
import dev.ostrom.server.identity.domain.GetDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.GetProfileUseCase
import dev.ostrom.server.identity.domain.ListAuditEventsUseCase
import dev.ostrom.server.identity.domain.ListTokensUseCase
import dev.ostrom.server.identity.domain.PollDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.RevokeTokenUseCase
import dev.ostrom.server.identity.domain.RotateTokenUseCase
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.StartDeviceAuthorizationUseCase
import dev.ostrom.server.identity.domain.WebSessionsOfUserUseCase
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.SignOutListener

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
)
