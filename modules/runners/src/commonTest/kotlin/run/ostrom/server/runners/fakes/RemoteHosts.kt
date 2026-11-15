// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.fakes

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.SignedInDevice
import run.ostrom.server.kernel.auth.SignedInDevices
import run.ostrom.server.kernel.auth.TokenAuthenticator
import kotlin.uuid.Uuid

/** Alice's laptop, the device of her sign-in `alice-laptop`. */
val LAPTOP: Uuid = Uuid.parse("00000000-0000-0000-0000-0000001a7e01")

/** Alice's desktop, the device of her sign-in `alice-desktop`. */
val DESKTOP: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000de5c0")

/** Bob's laptop, the device of his sign-in `bob-laptop`. */
val BOB_LAPTOP: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000b1a70")

/**
 * The CLI sign-ins of the remote session tests, as the identity module gives them: a person's, signed in, its
 * credential its token's id; `alice-old` is a CLI token from before devices.
 */
val REMOTE_LOGINS: Map<String, AuthenticatedPrincipal> = listOf(
    "alice-laptop" to "alice",
    "alice-desktop" to "alice",
    "alice-old" to "alice",
    "bob-laptop" to "bob",
).associate { (token, user) ->
    token to AuthenticatedPrincipal(
        user,
        PrincipalKind.USER,
        setOf("cloud:read", "cloud:write"),
        credentialId = "credential-$token",
        signedIn = true,
    )
}

/** [SignedInDevices] over [REMOTE_LOGINS]: each sign-in's device, renamed with [names]. */
class FakeSignedInDevices : SignedInDevices {
    val names = mutableMapOf(LAPTOP to "Alice's laptop", DESKTOP to "Alice's desktop", BOB_LAPTOP to "Bob's laptop")
    private val byCredential = mapOf(
        "credential-alice-laptop" to LAPTOP,
        "credential-alice-desktop" to DESKTOP,
        "credential-bob-laptop" to BOB_LAPTOP,
    )

    override suspend fun deviceOf(principal: AuthenticatedPrincipal): Uuid? =
        principal.credentialId?.let { byCredential[it] }?.takeIf { principal.signedIn }

    override suspend fun devices(ids: Collection<Uuid>): Map<Uuid, SignedInDevice> = ids.mapNotNull { id ->
        names[id]?.let { id to SignedInDevice(id, Uuid.NIL, it, "macos", "26.1") }
    }.toMap()
}

/** [TokenAuthenticator] knowing [REMOTE_LOGINS] only. */
object RemoteLogins : TokenAuthenticator {
    override suspend fun authenticate(token: String): AuthenticatedPrincipal? = REMOTE_LOGINS[token]
}
