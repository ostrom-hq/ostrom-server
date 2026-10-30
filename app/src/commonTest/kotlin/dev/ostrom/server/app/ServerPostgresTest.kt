package dev.ostrom.server.app

import dev.ostrom.server.kernel.database.MigrationRunner
import dev.ostrom.server.kernel.database.SchemaMigration
import dev.ostrom.server.kernel.http.HealthResponse
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** Every registered module together, on a real PostgreSQL. */
class ServerPostgresTest {
    @Test
    fun everyModuleMigratesAndTheServerIsHealthy() = serverTest(serverModules()) { server ->
        val response = server.client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(mapOf("database" to "ok"), response.body<HealthResponse>().checks)
    }

    @Test
    fun moduleNamesAreUnique() {
        val names = serverModules().map { it.name }
        assertEquals(names.toSet().size, names.size)
    }

    @Test
    fun migrationsAreAppliedOnceAndAnEditedOneIsRefused() = serverTest(emptyList()) { server ->
        val runner = server.koin.get<MigrationRunner>()
        val v1 = SchemaMigration(1, "create_probe", "CREATE TABLE probe_items (id BIGINT PRIMARY KEY);")
        val v2 = SchemaMigration(2, "add_probe_name", "ALTER TABLE probe_items ADD COLUMN name TEXT;")

        runner.migrate("probe", listOf(v1))
        runner.migrate("probe", listOf(v1, v2))
        runner.migrate("probe", listOf(v1, v2))

        val applied = server.driver.fetchAll("SELECT COUNT(*) AS n FROM _migrations_probe").getOrThrow()
        assertEquals(2L, applied.first().get("n").asLong())
        val edited = v1.copy(sql = "CREATE TABLE probe_items (id TEXT PRIMARY KEY);")
        assertFails { runner.migrate("probe", listOf(edited, v2)) }
    }

    @Test
    fun twoModulesKeepSeparateVersionHistories() = serverTest(emptyList()) { server ->
        val runner = server.koin.get<MigrationRunner>()
        runner.migrate("alpha", listOf(SchemaMigration(1, "create", "CREATE TABLE alpha_items (id BIGINT);")))
        runner.migrate("beta", listOf(SchemaMigration(1, "create", "CREATE TABLE beta_items (id BIGINT);")))

        val tables = server.driver.fetchAll(
            "SELECT table_name FROM information_schema.tables WHERE table_name LIKE '_migrations_%' ORDER BY table_name",
        ).getOrThrow().map { it.get("table_name").asString() }
        assertTrue(tables.containsAll(listOf("_migrations_alpha", "_migrations_beta")))
    }
}
