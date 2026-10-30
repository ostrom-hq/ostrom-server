// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.http

import io.github.smyrgeorge.sqlx4k.Driver
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One dependency `/health` checks. Modules may bind more in Koin (`single<HealthCheck>(named("…"))`). */
interface HealthCheck {
    /** The name shown in `/health` (`database`). */
    val name: String

    /**
     * Checks the dependency.
     *
     * @return True when it answers.
     */
    suspend fun isUp(): Boolean
}

/**
 * Checks that PostgreSQL answers `SELECT 1` in time.
 *
 * @param driver The database.
 * @param timeout How long to wait for the answer.
 */
class DatabaseHealthCheck(private val driver: Driver, private val timeout: Duration = 2.seconds) : HealthCheck {
    override val name: String = "database"

    override suspend fun isUp(): Boolean = try {
        withTimeout(timeout) { driver.fetchAll("SELECT 1").isSuccess }
    } catch (_: TimeoutCancellationException) {
        false
    }
}

/**
 * The answer of `/health`.
 *
 * @property status `ok` when every check passes, `degraded` otherwise (with a 503).
 * @property version The server version.
 * @property checks Each check by name, `ok` or `down`.
 */
@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val checks: Map<String, String>,
)

/** The paths of the health route: the probes' `/health`, and the API contract's. */
internal val HEALTH_PATHS = setOf("/health", "/api/v1/health")

/**
 * `GET /health`, unauthenticated, for load balancers and orchestrators: 200 when every check passes, 503 otherwise.
 * Also served as `/api/v1/health`, its path in the API contract (`status` and `version`, plus the checks).
 *
 * @param checks The checks to run.
 * @param version The server version.
 */
fun Route.healthRoutes(checks: List<HealthCheck>, version: String) {
    for (path in HEALTH_PATHS) get(path) {
        val results = checks.associate { check ->
            val up = try {
                check.isUp()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            check.name to if (up) "ok" else "down"
        }
        val healthy = results.values.all { it == "ok" }
        call.respond(
            if (healthy) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            HealthResponse(if (healthy) "ok" else "degraded", version, results),
        )
    }
}
