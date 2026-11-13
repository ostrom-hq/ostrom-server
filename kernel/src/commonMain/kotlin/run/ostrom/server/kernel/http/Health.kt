// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.http

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

/** One dependency `/ready` checks. Modules may bind more in Koin (`single<HealthCheck>(named("…"))`). */
interface HealthCheck {
    /** The name shown in `/ready` (`database`). */
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
 * The answer of `/health` and `/ready`.
 *
 * @property status `ok` for `/health`; for `/ready`, `ready`, `draining` or `degraded` (with a 503).
 * @property version The server version.
 * @property checks Each check by name, `ok` or `down`; none on `/health`, which checks only the process.
 */
@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val checks: Map<String, String>,
)

/** The paths of the health route: the probes' `/health`, and the API contract's. */
internal val HEALTH_PATHS = setOf("/health", "/api/v1/health")

/** The readiness probe's path. */
internal const val READY_PATH = "/ready"

/** The probes' paths, logged at DEBUG so that probes do not flood the logs. */
internal val PROBE_PATHS = HEALTH_PATHS + READY_PATH

/**
 * `GET /health`, unauthenticated, the liveness probe: 200 `ok` as long as the process answers HTTP, without any
 * check, as TeamCode's (`docs/replicas.md`, audit SD-04): a database outage makes every replica unready, never
 * restarts them all at once, and a restart would not bring the database back. Also served as `/api/v1/health`, its
 * path in the API contract. A draining server stays healthy.
 *
 * `GET /ready`, unauthenticated, the readiness probe: 503 with `draining` once the server stops taking traffic, 503
 * `degraded` when a check fails (the database), 200 `ready` otherwise, with each check's result.
 *
 * @param checks The checks to run.
 * @param version The server version.
 * @param readiness Whether the server takes new traffic.
 */
fun Route.healthRoutes(checks: List<HealthCheck>, version: String, readiness: Readiness) {
    for (path in HEALTH_PATHS) get(path) {
        call.respond(HttpStatusCode.OK, HealthResponse("ok", version, emptyMap()))
    }
    get(READY_PATH) {
        val results = if (readiness.isDraining) emptyMap() else runChecks(checks)
        val status = when {
            readiness.isDraining -> "draining"
            results.values.all { it == "ok" } -> "ready"
            else -> "degraded"
        }
        call.respond(
            if (status == "ready") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            HealthResponse(status, version, results),
        )
    }
}

private suspend fun runChecks(checks: List<HealthCheck>): Map<String, String> = checks.associate { check ->
    val up = try {
        check.isUp()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }
    check.name to if (up) "ok" else "down"
}
