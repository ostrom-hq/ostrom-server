// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.integrations.github.domain.CreatedGitHubApp
import run.ostrom.server.integrations.github.domain.GitHubAppCredentials
import run.ostrom.server.integrations.github.domain.GitHubAppStore
import run.ostrom.server.secrets.domain.SecretCipher
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.time.Instant

/**
 * [GitHubAppStore] on PostgreSQL. The App's private key, client secret and webhook secret are sealed with the
 * secrets module's cipher (AES-256-GCM with the instance's secrets key), each bound to the App and the field
 * (`github-app:<id>:<field>`): an envelope moved to another field or row does not open. A value that does not open
 * (the secrets key changed) is read as absent: the App is then named but cannot sign, and the flow stays closed.
 *
 * @param driver The connection pool.
 * @param cipher Seals the secrets.
 */
class PostgresGitHubAppStore(private val driver: Driver, private val cipher: SecretCipher) : GitHubAppStore {
    override suspend fun app(): GitHubAppCredentials? {
        val row = driver.fetchAll(
            Statement.create(
                "SELECT app_id, slug, client_id, private_key_sealed, client_secret_sealed, webhook_secret_sealed " +
                    "FROM github_apps",
            ),
        ).getOrThrow().firstOrNull() ?: return null
        val id = row.get("app_id").asLong().toString()
        return GitHubAppCredentials(
            appId = id,
            slug = row.get("slug").asString(),
            privateKeyPem = open(row.get("private_key_sealed").asString(), id, KEY),
            webhookSecret = row.get("webhook_secret_sealed").asStringOrNull()?.let { open(it, id, WEBHOOK) },
            clientId = row.get("client_id").asString(),
            clientSecret = open(row.get("client_secret_sealed").asString(), id, CLIENT),
        )
    }

    override suspend fun saveApp(app: CreatedGitHubApp, by: String, at: Instant): Boolean {
        val id = app.id.toString()
        val statement = Statement.create(
            "INSERT INTO github_apps (app_id, slug, owner, client_id, private_key_sealed, client_secret_sealed, " +
                "webhook_secret_sealed, created_by, created_at) VALUES (:id, :slug, :owner, :client, :key, " +
                ":secret, :webhook, :by, :at) ON CONFLICT (singleton) DO NOTHING",
        ).bind("id", app.id).bind("slug", app.slug).bind("owner", app.owner).bind("client", app.clientId)
            .bind("key", seal(app.privateKeyPem, id, KEY)).bind("secret", seal(app.clientSecret, id, CLIENT))
            .bind("webhook", app.webhookSecret?.let { seal(it, id, WEBHOOK) }).bind("by", by).bind("at", at)
        return driver.execute(statement).getOrThrow() == 1L
    }

    override suspend fun saveState(stateHash: String, by: String, expiresAt: Instant) {
        driver.execute(
            Statement.create(
                "INSERT INTO github_manifest_states (state_hash, created_by, expires_at) VALUES (:hash, :by, :expires)",
            ).bind("hash", stateHash).bind("by", by).bind("expires", expiresAt),
        ).getOrThrow()
    }

    override suspend fun takeState(stateHash: String, now: Instant): String? {
        val row = driver.fetchAll(
            Statement.create(
                "DELETE FROM github_manifest_states WHERE state_hash = :hash RETURNING created_by, expires_at",
            ).bind("hash", stateHash),
        ).getOrThrow().firstOrNull() ?: return null
        return if (row.get("expires_at").asInstant() > now) row.get("created_by").asString() else null
    }

    private suspend fun seal(value: String, appId: String, field: String): String =
        cipher.seal(value.encodeToByteArray(), "github-app:$appId:$field")

    private suspend fun open(envelope: String, appId: String, field: String): String? =
        cipher.open(envelope, "github-app:$appId:$field")?.decodeToString()

    private companion object {
        const val KEY = "private-key"
        const val CLIENT = "client-secret"
        const val WEBHOOK = "webhook-secret"
    }
}
