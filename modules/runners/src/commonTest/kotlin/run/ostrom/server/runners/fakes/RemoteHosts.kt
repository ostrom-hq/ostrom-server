// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.fakes

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.DaemonFolder
import run.ostrom.server.kernel.auth.DaemonListingRequest
import run.ostrom.server.kernel.auth.DaemonPresence
import run.ostrom.server.kernel.auth.DeviceDaemons
import run.ostrom.server.kernel.auth.OwnedDevice
import run.ostrom.server.secrets.domain.SecretCipher
import kotlin.time.Instant
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

/** Alice's account (her sign-ins' subject in the daemon tests). */
val ALICE_USER: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000a11ce")

/** A sign-in of [REMOTE_LOGINS] whose subject is Alice's account. */
fun aliceLogin(token: String): AuthenticatedPrincipal =
    REMOTE_LOGINS.getValue(token).copy(subjectId = ALICE_USER.toString())

/** [DeviceDaemons] in memory: each device's presence, folders and listings; Alice owns [LAPTOP] and [DESKTOP]. */
class FakeDeviceDaemons : DeviceDaemons {
    /** The owner of each device. */
    val owners = mutableMapOf(LAPTOP to ALICE_USER, DESKTOP to ALICE_USER, BOB_LAPTOP to Uuid.random())
    val seen = mutableMapOf<Uuid, Triple<Instant, String?, List<String>>>()
    val folders = mutableMapOf<Uuid, List<DaemonFolder>>()
    val requests = linkedMapOf<Uuid, Pair<Uuid, DaemonListingRequest>>()
    val answers = mutableMapOf<Uuid, String>()
    val dropped = mutableListOf<Uuid>()
    var touches = 0

    override suspend fun touch(deviceId: Uuid, version: String?, features: List<String>, now: Instant) {
        touches++
        seen[deviceId] = Triple(now, version, features)
    }

    override suspend fun offline(deviceId: Uuid) {
        seen.remove(deviceId)
    }

    override suspend fun publish(deviceId: Uuid, folders: List<DaemonFolder>) {
        this.folders[deviceId] = folders
    }

    override suspend fun owned(userId: Uuid, deviceId: Uuid, now: Instant): OwnedDevice? {
        if (owners[deviceId] != userId) return null
        val presence = seen[deviceId]?.let { (at, version, features) -> DaemonPresence.of(at, version, now, features) }
        return OwnedDevice(deviceId, "Alice's laptop", presence, folders[deviceId].orEmpty())
    }

    override suspend fun askListing(
        deviceId: Uuid,
        folderId: String,
        limit: Int,
        before: String?,
        now: Instant,
        expiresAt: Instant,
    ): Uuid {
        val id = Uuid.random()
        requests[id] = deviceId to DaemonListingRequest(id, folderId, limit, before)
        return id
    }

    override suspend fun listings(deviceId: Uuid, now: Instant, limit: Int): List<DaemonListingRequest> =
        requests.values.filter { it.first == deviceId && it.second.requestId !in answers }.map { it.second }.take(limit)

    override suspend fun answerListing(deviceId: Uuid, requestId: Uuid, sealed: String, now: Instant): Boolean {
        val open = requests[requestId]?.first == deviceId && requestId !in answers
        if (open) answers[requestId] = sealed
        return open
    }

    override suspend fun listingAnswer(requestId: Uuid): String? = answers[requestId]

    override suspend fun dropListing(requestId: Uuid) {
        dropped += requestId
        requests.remove(requestId)
        answers.remove(requestId)
    }
}

/** A [SecretCipher] that only binds its context: `context|plaintext`, opened by the same context only. */
object ContextCipher : SecretCipher {
    override suspend fun seal(plaintext: ByteArray, context: String): String = "$context|${plaintext.decodeToString()}"

    override suspend fun open(envelope: String, context: String): ByteArray? =
        envelope.takeIf { it.startsWith("$context|") }?.removePrefix("$context|")?.encodeToByteArray()
}
