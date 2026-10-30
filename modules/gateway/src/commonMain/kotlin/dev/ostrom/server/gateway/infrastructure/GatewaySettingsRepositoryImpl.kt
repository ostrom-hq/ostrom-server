// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.infrastructure

import dev.ostrom.server.gateway.domain.GatewayOrgConfig
import dev.ostrom.server.gateway.domain.GatewaySettings
import dev.ostrom.server.gateway.domain.GatewaySettingsRepository
import dev.ostrom.server.gateway.domain.ModelPolicy
import dev.ostrom.server.secrets.infrastructure.executeInOrg
import dev.ostrom.server.secrets.infrastructure.fetchInOrg
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * [GatewaySettingsRepository] on PostgreSQL ([GatewayMigrations]). Every query runs confined to the org it names
 * (`fetchInOrg`, `executeInOrg`): row-level security hides and refuses the rows of every other org.
 *
 * @param driver The database.
 */
class GatewaySettingsRepositoryImpl(private val driver: Driver) : GatewaySettingsRepository {
    override suspend fun get(orgId: Uuid): GatewaySettings = driver.fetchInOrg(
        orgId,
        Statement.create(
            "SELECT anthropic_key_secret, openai_key_secret, openrouter_key_secret, use_shared_accounts " +
                "FROM gateway_settings WHERE org_id = :org_id",
        ).bind("org_id", orgId),
    ).getOrThrow().firstOrNull()?.let(::settingsOf) ?: GatewaySettings()

    override suspend fun forCall(orgId: Uuid): GatewayOrgConfig = driver.fetchInOrg(
        orgId,
        Statement.create(FOR_CALL).bind("org_id", orgId),
    ).getOrThrow().first().let { row ->
        GatewayOrgConfig(
            settings = if (row.get("settings_org").asStringOrNull() == null) GatewaySettings() else settingsOf(row),
            policy = if (row.get("policy_org").asStringOrNull() == null) ModelPolicy() else policyOf(row),
        )
    }

    private fun settingsOf(row: ResultSet.Row) = GatewaySettings(
        anthropicKeySecret = row.get("anthropic_key_secret").asStringOrNull(),
        openaiKeySecret = row.get("openai_key_secret").asStringOrNull(),
        openrouterKeySecret = row.get("openrouter_key_secret").asStringOrNull(),
        useSharedAccounts = row.get("use_shared_accounts").asBoolean(),
    )

    private fun policyOf(row: ResultSet.Row) = ModelPolicy(
        allowed = Json.decodeFromString(MODELS, row.get("allowed").asString()),
        default = row.get("default_model").asStringOrNull(),
        byMember = Json.decodeFromString(BY_MEMBER, row.get("by_member").asString()),
    )

    override suspend fun put(orgId: Uuid, settings: GatewaySettings) {
        driver.executeInOrg(
            orgId,
            Statement.create(UPSERT_SETTINGS)
                .bind("org_id", orgId)
                .bindText("anthropic", settings.anthropicKeySecret)
                .bindText("openai", settings.openaiKeySecret)
                .bindText("openrouter", settings.openrouterKeySecret)
                .bind("shared", settings.useSharedAccounts),
        ).getOrThrow()
    }

    override suspend fun policy(orgId: Uuid): ModelPolicy = driver.fetchInOrg(
        orgId,
        Statement.create("SELECT allowed, default_model, by_member FROM gateway_model_policies WHERE org_id = :org_id")
            .bind("org_id", orgId),
    ).getOrThrow().firstOrNull()?.let(::policyOf) ?: ModelPolicy()

    override suspend fun putPolicy(orgId: Uuid, policy: ModelPolicy) {
        driver.executeInOrg(
            orgId,
            Statement.create(UPSERT_POLICY)
                .bind("org_id", orgId)
                .bind("allowed", Json.encodeToString(MODELS, policy.allowed))
                .bindText("default", policy.default)
                .bind("by_member", Json.encodeToString(BY_MEMBER, policy.byMember)),
        ).getOrThrow()
    }

    private fun Statement.bindText(name: String, value: String?): Statement =
        if (value == null) bindNull(name, String::class) else bind(name, value)

    private companion object {
        // One row whatever was set: the org's id, joined to each table.
        const val FOR_CALL =
            "WITH org AS (SELECT CAST(:org_id AS UUID) AS id) " +
                "SELECT CAST(s.org_id AS TEXT) AS settings_org, s.anthropic_key_secret, s.openai_key_secret, " +
                "s.openrouter_key_secret, s.use_shared_accounts, " +
                "CAST(p.org_id AS TEXT) AS policy_org, p.allowed, p.default_model, p.by_member " +
                "FROM org LEFT JOIN gateway_settings s ON s.org_id = org.id " +
                "LEFT JOIN gateway_model_policies p ON p.org_id = org.id"

        const val UPSERT_SETTINGS =
            "INSERT INTO gateway_settings (org_id, anthropic_key_secret, openai_key_secret, openrouter_key_secret, " +
                "use_shared_accounts) VALUES (:org_id, :anthropic, :openai, :openrouter, :shared) " +
                "ON CONFLICT (org_id) DO UPDATE SET anthropic_key_secret = EXCLUDED.anthropic_key_secret, " +
                "openai_key_secret = EXCLUDED.openai_key_secret, " +
                "openrouter_key_secret = EXCLUDED.openrouter_key_secret, " +
                "use_shared_accounts = EXCLUDED.use_shared_accounts"

        const val UPSERT_POLICY =
            "INSERT INTO gateway_model_policies (org_id, allowed, default_model, by_member) " +
                "VALUES (:org_id, :allowed, :default, :by_member) " +
                "ON CONFLICT (org_id) DO UPDATE SET allowed = EXCLUDED.allowed, " +
                "default_model = EXCLUDED.default_model, by_member = EXCLUDED.by_member"
    }
}

private val MODELS = ListSerializer(String.serializer())
private val BY_MEMBER = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
