// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.domain

import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The kind of token a workstation holds: the CLI's, issued by `ostrom login` (device flow) or the launcher's login. A
 * device is one live CLI token; signing it out revokes the token.
 */
private val DEVICE_KIND: TokenKind = TokenKind.CLI

/** Lists the workstations where the person is signed in. */
interface ListDevicesUseCase {
    /**
     * Lists them.
     *
     * @param userId The person.
     *
     * @return Their live CLI tokens, newest first.
     */
    suspend operator fun invoke(userId: Uuid): List<ApiToken>
}

/**
 * [ListDevicesUseCase] on the tokens repository.
 *
 * @param tokens The tokens.
 * @param clock The clock.
 */
class ListDevicesUseCaseImpl(private val tokens: ApiTokensRepository, private val clock: Clock) : ListDevicesUseCase {
    override suspend fun invoke(userId: Uuid): List<ApiToken> = tokens.listLive(userId, setOf(DEVICE_KIND), clock.now())
}

/** Signs one of the person's workstations out: its CLI token stops working at once. */
interface SignOutDeviceUseCase {
    /**
     * Signs it out.
     *
     * @param userId The person.
     * @param deviceId The device (its CLI token).
     *
     * @throws DeviceNotFoundException When it is absent, another person's, not a CLI token, or already signed out.
     */
    suspend operator fun invoke(userId: Uuid, deviceId: Uuid)
}

/**
 * [SignOutDeviceUseCase] on the tokens repository.
 *
 * @param tokens The tokens.
 * @param audit The audit log.
 * @param clock The clock.
 */
class SignOutDeviceUseCaseImpl(
    private val tokens: ApiTokensRepository,
    private val audit: AuditLog,
    private val clock: Clock,
) : SignOutDeviceUseCase {
    override suspend fun invoke(userId: Uuid, deviceId: Uuid) {
        // `revoke` refuses a token already revoked; the kind is checked first, so no other token is signed out here.
        val device = tokens.get(userId, deviceId)
        if (device?.kind != DEVICE_KIND || !tokens.revoke(userId, deviceId, clock.nowMillis())) {
            throw DeviceNotFoundException()
        }
        audit.record(AuditEntry(null, userId, "token.revoked", "token", deviceId.toString(), mapOf("kind" to "cli")))
    }
}
