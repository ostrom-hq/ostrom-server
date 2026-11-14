// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.CancellationLimitException
import run.ostrom.server.gateway.domain.RequestCancellationRepository
import run.ostrom.server.gateway.infrastructure.GatewayCancellations
import run.ostrom.server.gateway.infrastructure.RequestCancellationRepositoryImpl
import run.ostrom.server.gateway.infrastructure.advisoryLock
import run.ostrom.server.gateway.infrastructure.cancellationCounts
import run.ostrom.server.gateway.infrastructure.cancellationInsert
import run.ostrom.server.gateway.infrastructure.cancellationLockKey
import run.ostrom.server.gateway.infrastructure.cancellationPurge
import run.ostrom.server.gateway.infrastructure.cancellationRead
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.SharingModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The cancellation markers on real PostgreSQL, as the serving role under row-level security, with two repositories as
 * two replicas: a marker one leaves the other reads, scoped to its member and org, bounded and expiring.
 */
class RequestCancellationPostgresTest {
    private val modules = listOf(SecretsModule, SharingModule, GatewayModule)
    private val overrides = module { single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) } }
    private val ada = Gw.memberA.memberId
    private val bob = Gw.otherA.memberId

    private fun TestServer.replicas(): List<RequestCancellationRepository> =
        listOf(koin.get<RequestCancellationRepository>(), RequestCancellationRepositoryImpl(koin.get<JdbcDatabase>()))

    /** [count] markers of [org], one member each unless [member] is given, alive for a minute. */
    private fun markers(org: Uuid, count: Int, member: Uuid? = null) =
        "INSERT INTO gateway_cancellations (org_id, member_id, request_id, expires_at) " +
            "SELECT CAST('$org' AS uuid), ${member?.let { "CAST('$it' AS uuid)" } ?: "gen_random_uuid()"}, " +
            "gen_random_uuid(), now() + INTERVAL '1 minute' FROM generate_series(1, $count) AS i"

    @Test
    fun aMarkerOneReplicaLeavesTheOtherReadsForItsMemberOnly() = serverTest(modules, overrides) { server ->
        val (one, two) = server.replicas()
        val id = Uuid.random()
        assertFalse(two.isCancelled(Gw.orgA, ada, id))
        one.cancel(Gw.orgA, ada, id)
        one.cancel(Gw.orgA, ada, id)
        assertTrue(two.isCancelled(Gw.orgA, ada, id))
        assertFalse(two.isCancelled(Gw.orgA, bob, id), "Another member's call of the same id goes on")
        assertFalse(two.isCancelled(Gw.orgB, ada, id), "Another org sees nothing")
        val rows = server.database.superuser.fetchAll("SELECT request_id FROM gateway_cancellations").getOrThrow()
        assertEquals(1, rows.size, "Sent twice, one marker")

        // An expired marker stops nothing and is dropped as the next one is written.
        server.database.superuser.execute("UPDATE gateway_cancellations SET expires_at = now()").getOrThrow()
        assertFalse(two.isCancelled(Gw.orgA, ada, id))
        one.cancel(Gw.orgA, bob, Uuid.random())
        val left = server.database.superuser.fetchAll("SELECT member_id FROM gateway_cancellations").getOrThrow()
        assertEquals(1, left.size)

        val unfiltered = server.koin.get<JdbcDatabase>().inOrg(Gw.orgB) {
            GatewayCancellations.select(GatewayCancellations.orgId).count()
        }
        assertEquals(0L, unfiltered, "No WHERE, no leak")
    }

    @Test
    fun aMemberHoldsTwoHundredAndFiftySixMarkersAndAnOrgFourThousandAndNinetySix() =
        serverTest(modules, overrides) { server ->
            val (one, _) = server.replicas()
            val fixtures = server.database.superuser
            fixtures.execute(markers(Gw.orgA, RequestCancellationRepository.MEMBER_LIMIT, member = ada)).getOrThrow()
            assertFailsWith<CancellationLimitException> { one.cancel(Gw.orgA, ada, Uuid.random()) }
            one.cancel(Gw.orgA, bob, Uuid.random())

            val rest = RequestCancellationRepository.ORG_LIMIT - RequestCancellationRepository.MEMBER_LIMIT - 1
            fixtures.execute(markers(Gw.orgA, rest)).getOrThrow()
            assertFailsWith<CancellationLimitException> { one.cancel(Gw.orgA, bob, Uuid.random()) }
            one.cancel(Gw.orgB, ada, Uuid.random())
        }

    @Test
    fun theQueriesOfACancellationScanNoTable() = serverTest(modules, overrides) { server ->
        val fixtures = server.database.superuser
        fixtures.execute(markers(Gw.orgB, count = 5_000)).getOrThrow()
        fixtures.execute("VACUUM ANALYZE gateway_cancellations").getOrThrow()
        val requestId = Uuid.random()
        val statements: List<StatementBuilder.() -> Statement<*>> = listOf(
            { advisoryLock(cancellationLockKey(Gw.orgA)) },
            { cancellationPurge(Gw.orgA) },
            { cancellationCounts(Gw.orgA, ada, requestId).query },
            { cancellationInsert(Gw.orgA, ada, requestId) },
            { cancellationRead(Gw.orgA, ada, requestId) },
        )
        for (statement in statements) {
            val plan = server.koin.get<JdbcDatabase>().inOrg(Gw.orgA) { plan(statement).also { rollback() } }
            assertFalse("Seq Scan" in plan, "A cancellation statement scans a table:\n$plan")
        }
    }
}
