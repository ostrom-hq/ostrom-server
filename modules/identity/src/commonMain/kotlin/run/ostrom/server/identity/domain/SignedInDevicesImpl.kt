// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.SignedInDevice
import run.ostrom.server.kernel.auth.SignedInDevices
import kotlin.uuid.Uuid

/**
 * [SignedInDevices] on the tokens and the devices: a principal's device is the one its CLI token is a sign-in of
 * (TeamCode's `deviceOf`, the device of the login session). Only a person's CLI token has one; the token of
 * `ostrom login --admin`, a browser session, the app, a personal token, a service key and an agent have none.
 *
 * @param tokens The account-level tokens.
 * @param devices The registered devices.
 */
class SignedInDevicesImpl(
    private val tokens: ApiTokensRepository,
    private val devices: DevicesRepository,
) : SignedInDevices {
    override suspend fun deviceOf(principal: AuthenticatedPrincipal): Uuid? {
        val person = principal.kind == PrincipalKind.USER && principal.signedIn
        val userId = Uuid.parseOrNull(principal.subjectId)?.takeIf { person }
        val credential = principal.credentialId?.let(Uuid::parseOrNull)
        if (userId == null || credential == null) return null
        val token = tokens.get(userId, credential)?.takeIf { it.kind == TokenKind.CLI && it.revokedAt == null }
        return token?.deviceId
    }

    override suspend fun devices(ids: Collection<Uuid>): Map<Uuid, SignedInDevice> =
        devices.getAll(ids).associate { it.id to SignedInDevice(it.id, it.userId, it.name, it.platform, it.osVersion) }
}
