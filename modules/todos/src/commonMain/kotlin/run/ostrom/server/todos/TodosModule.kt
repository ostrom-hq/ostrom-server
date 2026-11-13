// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.kernel.database.SchemaMigration
import run.ostrom.server.kernel.module.ServerModule
import run.ostrom.server.todos.domain.ChangeTodoUseCase
import run.ostrom.server.todos.domain.ChangeTodoUseCaseImpl
import run.ostrom.server.todos.domain.ReadTodoUseCase
import run.ostrom.server.todos.domain.ReadTodoUseCaseImpl
import run.ostrom.server.todos.domain.StopHookSettingsUseCase
import run.ostrom.server.todos.domain.StopHookSettingsUseCaseImpl
import run.ostrom.server.todos.domain.StopTodoUseCase
import run.ostrom.server.todos.domain.StopTodoUseCaseImpl
import run.ostrom.server.todos.domain.TodoAccess
import run.ostrom.server.todos.domain.TodoArtifacts
import run.ostrom.server.todos.domain.TodoSessions
import run.ostrom.server.todos.domain.TodoVerifier
import run.ostrom.server.todos.domain.TodoWaits
import run.ostrom.server.todos.domain.TodosRepository
import run.ostrom.server.todos.infrastructure.ArtifactsTodoArtifacts
import run.ostrom.server.todos.infrastructure.AutomationsTodoWaits
import run.ostrom.server.todos.infrastructure.SessionsTodoSessions
import run.ostrom.server.todos.infrastructure.TodosMigrations
import run.ostrom.server.todos.infrastructure.TodosRepositoryImpl
import run.ostrom.server.todos.presentation.TodoCallers
import run.ostrom.server.todos.presentation.TodosRoutesDependencies
import run.ostrom.server.todos.presentation.todosRoutes
import io.ktor.server.routing.Route
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The todos module: a session's todo (TeamCode `docs/todos.md`), a plan reference and items whose end conditions
 * are checked, never taken on the agent's word, the Stop hook's questions and the per-member switch of that hook.
 * Its drop requests and stalls ask the session's person an ordinary wait (automations module); it reads the session
 * (sessions module) and the artifacts it verifies (artifacts module).
 */
object TodosModule : ServerModule {
    override val name: String = "todos"

    override val koinModule: Module = module {
        single<TodosRepository> { TodosRepositoryImpl(get(), get()) }
        single<TodoSessions> { SessionsTodoSessions(get(), get()) }
        single<TodoWaits> { AutomationsTodoWaits(get(), get(), get()) }
        single<TodoArtifacts> { ArtifactsTodoArtifacts(get()) }
        single { TodoAccess(get()) }
        single { TodoVerifier(get(), get(), get(), get()) }
        single<ReadTodoUseCase> { ReadTodoUseCaseImpl(get(), get()) }
        single<ChangeTodoUseCase> { ChangeTodoUseCaseImpl(get(), get(), get(), get()) }
        single<StopTodoUseCase> { StopTodoUseCaseImpl(get(), get(), get(), get()) }
        single<StopHookSettingsUseCase> { StopHookSettingsUseCaseImpl(get(), get()) }
        single { TodoCallers(get(), get()) }
        single { TodosRoutesDependencies(get(), get(), get(), get(), get(), get()) }
    }

    override val migrations: List<SchemaMigration> = TodosMigrations

    override fun routes(route: Route, koin: Koin) {
        route.todosRoutes(koin.get())
    }

    /** A session's todo and its Stop hook. */
    override suspend fun features(koin: Koin): Set<String> = setOf(ServerFeatures.TODOS)
}
