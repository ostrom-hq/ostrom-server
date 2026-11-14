// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.module

import run.ostrom.server.kernel.database.SchemaMigration
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import org.koin.core.Koin
import org.koin.core.module.Module

/**
 * A functional module of the server (identity, orgs, receipts…): its dependencies, its schema, its routes and its
 * background work. The app lists the modules once (`app/…/ServerModules.kt`); a module never reaches into another
 * module's tables or implementations, only into the interfaces that module exposes through Koin.
 * How to add one: docs/MODULES.md.
 */
interface ServerModule {
    /** The module name: lower-case, also the suffix of its Flyway history (`flyway_<name>`). */
    val name: String

    /** The Koin definitions of the module: use cases, repositories, and the routes' dependencies. */
    val koinModule: Module

    /** The schema migrations of the module, applied by `ostrom-server migrate` in the order of the module list. */
    val migrations: List<SchemaMigration> get() = emptyList()

    /**
     * Prepares the module once every module's migrations have run and before the server answers (seed rows,
     * register the instance key). Runs in module order; keep it short.
     *
     * @param koin Resolves the module's dependencies.
     */
    suspend fun initialize(koin: Koin) = Unit

    /**
     * Declares the module's routes.
     *
     * @param route The root route; wrap protected routes in `authenticated { }`.
     * @param koin Resolves the routes' dependencies.
     */
    fun routes(route: Route, koin: Koin) = Unit

    /**
     * The features this module serves **and** that are configured on this instance, as keys of ostrom-core's
     * `ServerFeatures` (`billing.v1` only with Stripe configured, `github.v1` only with a GitHub App…). The kernel
     * answers their union in `GET /api/v1/capabilities`, which clients read to show or hide a feature. Never a key
     * for a planned operation, nor one whose configuration is missing: a client would offer what the server refuses.
     * Read at every call of the route, after [initialize]: keep it cheap.
     *
     * @param koin Resolves the module's configuration.
     *
     * @return The feature keys; none by default.
     */
    suspend fun features(koin: Koin): Set<String> = emptySet()

    /**
     * Starts the module's background work (schedulers, listeners) once migrations have run. Work is launched in
     * [scope], which is cancelled when the server stops; never block here.
     *
     * @param scope The server's scope.
     * @param koin Resolves the work's dependencies.
     */
    fun start(scope: CoroutineScope, koin: Koin) = Unit
}
