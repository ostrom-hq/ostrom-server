// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.infrastructure

import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.FindSecretUseCase
import dev.ostrom.server.secrets.domain.RevealSecretsUseCase
import dev.ostrom.server.secrets.domain.Secret
import dev.ostrom.server.secrets.domain.SecretNotFoundException
import dev.ostrom.server.sharing.domain.CredentialSecrets
import dev.ostrom.server.sharing.domain.SecretCredential

/**
 * [CredentialSecrets] on the secrets module: the caller's own personal secret of that name, else the org's for an
 * admin (a member lends what is theirs, never a value the org keeps from them), decrypted on the server.
 *
 * @param find Finds a secret by name.
 * @param reveal Decrypts it.
 */
class SecretsCredentialSecrets(private val find: FindSecretUseCase, private val reveal: RevealSecretsUseCase) :
    CredentialSecrets {
    override suspend fun read(caller: Caller, name: String): SecretCredential? {
        val secret = named(caller, name, personal = true)
            ?: if (caller.role.isAdmin) named(caller, name, personal = false) else null
        val value = secret?.let { reveal(caller.orgId, listOf(it.id))[it.id] }
        // Deleted between the two reads: none.
        return if (secret == null || value == null) null else SecretCredential(secret.id, value.reveal())
    }

    private suspend fun named(caller: Caller, name: String, personal: Boolean): Secret? = try {
        find(caller, name, personal)
    } catch (_: SecretNotFoundException) {
        null
    }
}
