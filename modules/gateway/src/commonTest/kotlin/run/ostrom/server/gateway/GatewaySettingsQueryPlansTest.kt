// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.infrastructure.policyRead
import run.ostrom.server.gateway.infrastructure.pricesRead
import run.ostrom.server.gateway.infrastructure.settingsRead
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.SharingModule
import run.ostrom.server.testing.serverTest
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The reads every relayed call makes before it is sent on (`GatewaySettingsRepository.forCall`), under
 * `EXPLAIN ANALYZE` as the serving role with row-level security, over thousands of other orgs' rows: each is one
 * lookup by primary key, never a scan.
 */
class GatewaySettingsQueryPlansTest {
    private val modules = listOf(SecretsModule, SharingModule, GatewayModule)
    private val overrides = module { single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) } }

    @Test
    fun theReadsOfACallLookUpTheOrgByItsKey() = serverTest(modules, overrides) { server ->
        // Fixtures only: other orgs' rows and the statistics, which no request writes.
        val fixtures = server.database.superuser
        fixtures.execute(
            "INSERT INTO gateway_settings (org_id, use_shared_accounts, member_concurrency) " +
                "SELECT gen_random_uuid(), true, '{}' FROM generate_series(1, 5000)",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO gateway_model_policies (org_id, allowed, by_member) " +
                "SELECT gen_random_uuid(), '[]', '{}' FROM generate_series(1, 5000)",
        ).getOrThrow()
        fixtures.execute(
            "INSERT INTO gateway_api_prices (org_id, provider, model, effective_from_day, input_micros, " +
                "cache_write_micros, cache_read_micros, output_micros) " +
                "SELECT gen_random_uuid(), 'anthropic', 'm' || n, 20000, 1, 1, 1, 1 FROM generate_series(1, 5000) n",
        ).getOrThrow()
        listOf("gateway_settings", "gateway_model_policies", "gateway_api_prices").forEach {
            fixtures.execute("VACUUM ANALYZE $it").getOrThrow()
        }
        val database = server.koin.get<JdbcDatabase>()
        val reads = listOf(
            "gateway_settings" to settingsRead(Gw.orgA),
            "gateway_model_policies" to policyRead(Gw.orgA),
            "gateway_api_prices" to pricesRead(Gw.orgA),
        )
        for ((table, read) in reads) {
            val plan = database.inOrg(Gw.orgA) { plan { read } }
            assertFalse("Seq Scan" in plan, "The read of $table scans it:\n$plan")
        }
    }
}
