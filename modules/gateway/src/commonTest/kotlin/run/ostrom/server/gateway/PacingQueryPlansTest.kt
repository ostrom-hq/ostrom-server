// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import kotlin.time.Duration.Companion.minutes
import run.ostrom.server.gateway.domain.AdmissionRequest
import run.ostrom.server.gateway.domain.Workload
import run.ostrom.server.gateway.infrastructure.GatewayDirectives
import run.ostrom.server.gateway.infrastructure.pacingAdmittedUpdate
import run.ostrom.server.gateway.infrastructure.pacingRead
import run.ostrom.server.gateway.infrastructure.sessionDigest
import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.databaseNowPlus
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.SharingModule
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.jdbc.insert
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

/**
 * The capacity controller's statements on every admitted call (`pacingWait`, `pacingAdmitted` in the admission queue),
 * under `EXPLAIN ANALYZE` as the serving role with row-level security, over thousands of other orgs' and members'
 * advice: each reaches its rows by the primary key, never a scan.
 */
class PacingQueryPlansTest {
    private val modules = listOf(SecretsModule, SharingModule, GatewayModule)
    private val overrides = module { single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) } }
    private val session = "sess-plans"

    @Test
    fun theSpacingOfACallReadsAndWritesItsScopeByItsKey() = serverTest(modules, overrides) { server ->
        // Fixtures only: other orgs' advice, other members' of the org, and the statistics, which no request writes.
        server.superuserSql(
            "INSERT INTO gateway_directives (org_id, member_id, scope_key, id, pace, max_concurrent, " +
                "min_interval_ms, message, created_at, expires_at, context_key) " +
                "SELECT CASE WHEN n % 10 = 0 THEN ? ELSE gen_random_uuid() END, gen_random_uuid(), " +
                "CASE WHEN n % 2 = 0 THEN '' ELSE md5(n::text) END, 'dir_' || n, 'SLOW_DOWN', 1, 30000, 'm', now(), " +
                "now() + interval '3 minutes', 'k' FROM generate_series(1, 5000) n",
            listOf(UuidColumnType() to Gw.orgA),
        )
        val member = Gw.memberA.memberId
        for (scope in listOf("", sessionDigest(session))) {
            server.asSuperuser {
                GatewayDirectives.insert {
                    it[orgId] = Gw.orgA
                    it[memberId] = member
                    it[scopeKey] = scope
                    it[id] = "dir_a_$scope"
                    it[pace] = "SLOW_DOWN"
                    it[maxConcurrent] = 1
                    it[minIntervalMs] = 30_000
                    it[message] = "m"
                    it[createdAt] = databaseNow()
                    it[expiresAt] = databaseNowPlus(3.minutes)
                    it[contextKey] = "k"
                    it[lastAdmittedAt] = databaseNow()
                }
            }
        }
        server.vacuumAnalyze("gateway_directives")
        val database = server.koin.get<JdbcDatabase>()
        for (call in listOf(request(member, null), request(member, session))) {
            val read = database.inOrg(Gw.orgA) { plan { pacingRead(call) } }
            assertFalse("Seq Scan" in read, "The spacing's read scans gateway_directives:\n$read")
            val write = database.inOrg(Gw.orgA) { plan { pacingAdmittedUpdate(call) } }
            assertFalse("Seq Scan" in write, "The admission's record scans gateway_directives:\n$write")
        }
    }

    private fun request(member: Uuid, session: String?) =
        AdmissionRequest(Uuid.random(), Gw.orgA, member, "claude", Workload.INTERACTIVE, null, session = session)
}
