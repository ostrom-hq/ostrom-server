// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.integrations.github.domain.CreatedGitHubApp
import run.ostrom.server.integrations.github.domain.GitHubAppCredentials
import run.ostrom.server.integrations.github.domain.GitHubAppStore
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.secrets.domain.SecretCipher
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteReturning
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant

/**
 * [GitHubAppStore] on PostgreSQL. The App's private key, client secret and webhook secret are sealed with the
 * secrets module's cipher (AES-256-GCM with the instance's secrets key), each bound to the App and the field
 * (`github-app:<id>:<field>`): an envelope moved to another field or row does not open. A value that does not open
 * (the secrets key changed) is read as absent: the App is then named but cannot sign, and the flow stays closed.
 *
 * @param database The serving role's pool.
 * @param cipher Seals the secrets.
 */
class PostgresGitHubAppStore(private val database: JdbcDatabase, private val cipher: SecretCipher) :
    GitHubAppStore {
    override suspend fun app(): GitHubAppCredentials? {
        val a = GitHubApps
        val row = database.query { a.selectAll().firstOrNull() }
        if (row == null) return null
        val id = row[a.appId].toString()
        return GitHubAppCredentials(
            appId = id,
            slug = row[a.slug],
            privateKeyPem = open(row[a.privateKeySealed], id, KEY),
            webhookSecret = row[a.webhookSecretSealed]?.let { open(it, id, WEBHOOK) },
            clientId = row[a.clientId],
            clientSecret = row[a.clientSecretSealed]?.let { open(it, id, CLIENT) },
            owner = row[a.owner],
            version = row[a.version],
            updatedAt = row[a.updatedAt],
        )
    }

    // A first App: the one of the manifest flow, of the former variables, or entered by hand. The table's only key is
    // its singleton, so the bare ON CONFLICT DO NOTHING of `insertIgnore` is the conflict on it.
    override suspend fun saveApp(app: CreatedGitHubApp, by: String, at: Instant): Boolean {
        val sealed = sealed(app)
        return database.query {
            val a = GitHubApps
            a.insertIgnore {
                it.setApp(sealed)
                it[a.createdBy] = by
                it[a.createdAt] = at
                it[a.updatedBy] = by
                it[a.updatedAt] = at
            }.insertedCount == 1
        }
    }

    override suspend fun replaceApp(app: CreatedGitHubApp, expectedVersion: Int?, by: String, at: Instant): Boolean {
        if (expectedVersion == null) return saveApp(app, by, at)
        val sealed = sealed(app)
        return database.query {
            val a = GitHubApps
            a.update({ a.version eq expectedVersion }) {
                it.setApp(sealed)
                it[a.version] = a.version + 1
                it[a.updatedBy] = by
                it[a.updatedAt] = at
            } == 1
        }
    }

    // The App's fields, its key and secrets sealed under its id.
    private suspend fun sealed(app: CreatedGitHubApp): SealedApp {
        val id = app.id.toString()
        return SealedApp(
            app,
            seal(app.privateKeyPem, id, KEY),
            app.clientSecret?.let { seal(it, id, CLIENT) },
            app.webhookSecret?.let { seal(it, id, WEBHOOK) },
        )
    }

    private fun UpdateBuilder<*>.setApp(sealed: SealedApp) {
        val a = GitHubApps
        this[a.appId] = sealed.app.id
        this[a.slug] = sealed.app.slug
        this[a.owner] = sealed.app.owner
        this[a.clientId] = sealed.app.clientId
        this[a.privateKeySealed] = sealed.key
        this[a.clientSecretSealed] = sealed.secret
        this[a.webhookSecretSealed] = sealed.webhook
    }

    override suspend fun saveState(stateHash: String, by: String, expiresAt: Instant) {
        database.query {
            GitHubManifestStates.insert {
                it[GitHubManifestStates.stateHash] = stateHash
                it[createdBy] = by
                it[GitHubManifestStates.expiresAt] = expiresAt
            }
        }
    }

    override suspend fun takeState(stateHash: String, now: Instant): String? {
        val s = GitHubManifestStates
        val row = database.query { s.deleteReturning(where = { s.stateHash eq stateHash }).firstOrNull() }
        return if (row != null && row[s.expiresAt] > now) row[s.createdBy] else null
    }

    private suspend fun seal(value: String, appId: String, field: String): String =
        cipher.seal(value.encodeToByteArray(), "github-app:$appId:$field")

    private suspend fun open(envelope: String, appId: String, field: String): String? =
        cipher.open(envelope, "github-app:$appId:$field")?.decodeToString()

    /** The App with its key and secrets sealed. */
    private class SealedApp(val app: CreatedGitHubApp, val key: String, val secret: String?, val webhook: String?)

    private companion object {
        const val KEY = "private-key"
        const val CLIENT = "client-secret"
        const val WEBHOOK = "webhook-secret"
    }
}
