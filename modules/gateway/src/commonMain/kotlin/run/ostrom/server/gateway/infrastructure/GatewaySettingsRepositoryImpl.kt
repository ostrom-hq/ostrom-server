// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.gateway.domain.GatewayOrgConfig
import run.ostrom.server.gateway.domain.GatewaySettings
import run.ostrom.server.gateway.domain.GatewaySettingsRepository
import run.ostrom.server.gateway.domain.ModelPolicy
import run.ostrom.server.kernel.database.JdbcDatabase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.uuid.Uuid

/**
 * [GatewaySettingsRepository] on PostgreSQL through Exposed ([GatewaySettingsTable], [GatewayModelPolicies]). Every
 * query runs confined to the org it names ([JdbcDatabase.inOrg]) and filters on it: row-level security hides and
 * refuses the rows of every other org.
 *
 * @param database The JDBC pool.
 */
class GatewaySettingsRepositoryImpl(private val database: JdbcDatabase) : GatewaySettingsRepository {
    override suspend fun get(orgId: Uuid): GatewaySettings = database.inOrg(orgId) { readSettings(orgId) }

    // Two lookups by primary key in one transaction: what a call reads before it is relayed.
    override suspend fun forCall(orgId: Uuid): GatewayOrgConfig = database.inOrg(orgId) {
        GatewayOrgConfig(settings = readSettings(orgId), policy = readPolicy(orgId))
    }

    override suspend fun put(orgId: Uuid, settings: GatewaySettings) {
        database.inOrg(orgId) {
            GatewaySettingsTable.upsert(GatewaySettingsTable.orgId) {
                it[this.orgId] = orgId
                it[anthropicKeySecret] = settings.anthropicKeySecret
                it[openaiKeySecret] = settings.openaiKeySecret
                it[openrouterKeySecret] = settings.openrouterKeySecret
                it[useSharedAccounts] = settings.useSharedAccounts
                it[memberConcurrency] = Json.encodeToString(CONCURRENCY, settings.memberConcurrency)
            }
        }
    }

    override suspend fun policy(orgId: Uuid): ModelPolicy = database.inOrg(orgId) { readPolicy(orgId) }

    override suspend fun putPolicy(orgId: Uuid, policy: ModelPolicy) {
        database.inOrg(orgId) {
            GatewayModelPolicies.upsert(GatewayModelPolicies.orgId) {
                it[this.orgId] = orgId
                it[allowed] = Json.encodeToString(MODELS, policy.allowed)
                it[defaultModel] = policy.default
                it[byMember] = Json.encodeToString(BY_MEMBER, policy.byMember)
            }
        }
    }

    private fun readSettings(orgId: Uuid): GatewaySettings {
        val row = settingsRead(orgId).singleOrNull()
        return if (row == null) GatewaySettings() else row.toSettings()
    }

    private fun readPolicy(orgId: Uuid): ModelPolicy {
        val row = policyRead(orgId).singleOrNull()
        return if (row == null) ModelPolicy() else row.toPolicy()
    }
}

/** The org's settings, by primary key: read before every relayed call. */
internal fun settingsRead(orgId: Uuid): Query {
    val t = GatewaySettingsTable
    return t.select(
        t.anthropicKeySecret,
        t.openaiKeySecret,
        t.openrouterKeySecret,
        t.useSharedAccounts,
        t.memberConcurrency,
    )
        .where { t.orgId eq orgId }
}

/** The org's model policy, by primary key: read before every relayed call. */
internal fun policyRead(orgId: Uuid): Query {
    val t = GatewayModelPolicies
    return t.select(t.allowed, t.defaultModel, t.byMember).where { t.orgId eq orgId }
}

private fun ResultRow.toSettings(): GatewaySettings {
    val t = GatewaySettingsTable
    return GatewaySettings(
        anthropicKeySecret = this[t.anthropicKeySecret],
        openaiKeySecret = this[t.openaiKeySecret],
        openrouterKeySecret = this[t.openrouterKeySecret],
        useSharedAccounts = this[t.useSharedAccounts],
        memberConcurrency = Json.decodeFromString(CONCURRENCY, this[t.memberConcurrency]),
    )
}

private fun ResultRow.toPolicy(): ModelPolicy {
    val t = GatewayModelPolicies
    return ModelPolicy(
        allowed = Json.decodeFromString(MODELS, this[t.allowed]),
        default = this[t.defaultModel],
        byMember = Json.decodeFromString(BY_MEMBER, this[t.byMember]),
    )
}

private val MODELS = ListSerializer(String.serializer())
private val BY_MEMBER = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
private val CONCURRENCY = MapSerializer(String.serializer(), Int.serializer())
