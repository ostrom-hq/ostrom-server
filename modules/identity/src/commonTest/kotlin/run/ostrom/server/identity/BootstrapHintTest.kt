// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity

import run.ostrom.server.identity.domain.ApiTokensRepository
import run.ostrom.server.identity.domain.SignUpMode
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.SetupLinksRepository
import run.ostrom.server.identity.domain.RateLimitWindows
import run.ostrom.server.identity.fakes.InMemoryApiTokensRepository
import run.ostrom.server.identity.fakes.InMemorySetupLinks
import run.ostrom.server.identity.fakes.InMemoryUsersRepository
import run.ostrom.server.identity.fakes.SettableClock
import run.ostrom.server.identity.presentation.IdentityConfig
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.test.TestScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** The start tells the operator of an invite-only instance without an account to run `ostrom-server bootstrap`. */
class BootstrapHintTest {
    private val warnings = mutableListOf<String>()
    private val recorder = object : Logger {
        override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
            if (level == LogLevel.WARN) warnings += message
        }

        override fun with(fields: Map<String, Any?>): Logger = this
    }

    private fun TestScope.start(links: SetupLinksRepository, mode: SignUpMode) {
        val app = koinApplication {
            modules(
                module {
                    single<ApiTokensRepository> { InMemoryApiTokensRepository() }
                    single<SetupLinksRepository> { links }
                    single<RateLimitWindows> { NO_WINDOWS }
                    single<Clock> { SettableClock() }
                    single<Logger> { recorder }
                    single { IdentityConfig("http://localhost:8080", "http://localhost:5173", signUp = mode) }
                },
            )
        }
        IdentityModule.start(backgroundScope, app.koin)
        runCurrent()
        app.close()
    }

    private companion object {
        val NO_WINDOWS = object : RateLimitWindows {
            override suspend fun purgeExpired(): Long = 0
        }
    }

    @Test
    fun anInviteOnlyInstanceWithoutAccountSaysToBootstrapIt() = runTest {
        start(InMemorySetupLinks(InMemoryUsersRepository()), SignUpMode.INVITE)

        assertEquals(listOf(IdentityModule.BOOTSTRAP_HINT), warnings)
    }

    @Test
    fun anInstanceWithAnAccountOrOpenSaysNothing() = runTest {
        val users = InMemoryUsersRepository()
        val ada = Uuid.random()
        users.users[ada] = User(ada, "ada", "Ada", null, null, SettableClock().now)
        start(InMemorySetupLinks(users), SignUpMode.INVITE)
        start(InMemorySetupLinks(InMemoryUsersRepository()), SignUpMode.OPEN)

        assertEquals(emptyList(), warnings)
    }

    @Test
    fun aFailedCheckIsLoggedNotFatal() = runTest {
        val down = object : SetupLinksRepository by InMemorySetupLinks() {
            override suspend fun anyAccount(): Boolean = error("database down")
        }
        start(down, SignUpMode.INVITE)

        assertEquals(listOf("account check failed"), warnings)
    }

    @Test
    fun aCancelledCheckSaysNothing() = runTest {
        val stopping = object : SetupLinksRepository by InMemorySetupLinks() {
            override suspend fun anyAccount(): Boolean = throw CancellationException("stopping")
        }
        start(stopping, SignUpMode.INVITE)

        assertEquals(emptyList(), warnings)
    }
}
