// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.memory.domain.MemoryLimitException
import run.ostrom.server.memory.domain.MemoryNotFoundException
import run.ostrom.server.memory.domain.MemoryOrigin
import run.ostrom.server.memory.domain.MemoryRepository
import run.ostrom.server.memory.domain.MemoryScope
import run.ostrom.server.memory.domain.MemoryStatus
import run.ostrom.server.memory.domain.NewMemory
import run.ostrom.server.memory.fakes.Callers
import run.ostrom.server.memory.fakes.EVE
import run.ostrom.server.memory.fakes.ORG_A
import run.ostrom.server.memory.fakes.ORG_B
import run.ostrom.server.memory.fakes.Tokens
import run.ostrom.server.memory.fakes.caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The memory's storage on a real PostgreSQL: its limits, its expiry, what it keeps of a proposal, its purge. */
class MemoryRepositoryPostgresTest {
    private val overrides = module {
        single<TokenAuthenticator> { Tokens }
        single<CallerResolver> { Callers }
    }

    private fun repositoryTest(block: suspend (TestServer, MemoryRepository) -> Unit) =
        serverTest(listOf(MemoryModule), overrides) { server -> block(server, server.koin.get()) }

    private suspend fun TestServer.fill(
        org: Uuid,
        scope: MemoryScope,
        owner: String,
        count: Long,
        expires: String = "NULL",
    ) {
        database.superuser.execute(
            """
            INSERT INTO memory_entries (id, org_id, scope, owner_id, title, summary, content, refs, status, origin,
            author_id, expires_at, version, created_at, updated_at)
            SELECT gen_random_uuid(), '$org', '${scope.wire}', '$owner', 'Old ' || n, 's', 'c', '[]', 'active', 'human',
            '$owner', $expires, 1, now(), now() FROM generate_series(1, $count) AS n
            """.trimIndent(),
        ).getOrThrow()
    }

    @Test
    fun eachMemoryHoldsItsLimitExactly() = repositoryTest { server, repository ->
        val ada = caller()
        server.fill(ORG_A, MemoryScope.PERSONAL, ada.memberId, 1_999)
        repository.create(ada, note(MemoryScope.PERSONAL), MemoryOrigin.HUMAN, null)
        val personal = assertFailsWith<MemoryLimitException> {
            repository.create(ada, note(MemoryScope.PERSONAL), MemoryOrigin.HUMAN, null)
        }
        assertTrue("2000" in personal.message.orEmpty())
        // Another member's own memory and the organization's are apart.
        repository.create(caller(EVE, ORG_B), note(MemoryScope.PERSONAL), MemoryOrigin.HUMAN, null)
        repository.create(ada, note(MemoryScope.TEAM), MemoryOrigin.HUMAN, null)

        server.fill(ORG_A, MemoryScope.TEAM, "", 4_998)
        // Two members write the last place at once: the organization's lock lets one in.
        val outcomes = coroutineScope {
            (1..2).map {
                async { runCatching { repository.create(ada, note(MemoryScope.TEAM), MemoryOrigin.HUMAN, null) } }
            }.awaitAll()
        }
        assertEquals(1, outcomes.count { it.isSuccess })
        assertTrue(outcomes.single { it.isFailure }.exceptionOrNull() is MemoryLimitException)
    }

    @Test
    fun aProposalCountsButIsNotReadAndAnExpiredEntryIsGone() = repositoryTest { server, repository ->
        val ada = caller()
        val proposed = repository.create(ada, note(MemoryScope.TEAM), MemoryOrigin.AGENT, "s-1", MemoryStatus.PROPOSED)
        assertEquals(MemoryStatus.PROPOSED, proposed.status)
        assertEquals("s-1", proposed.sessionId)
        assertFailsWith<MemoryNotFoundException> { repository.read(ada, proposed.id) }
        assertTrue(repository.index(ada, null, emptyList(), null, 50).entries.isEmpty())

        server.fill(ORG_A, MemoryScope.TEAM, "", 1, expires = "now() - INTERVAL '1 minute'")
        assertTrue(repository.index(ada, MemoryScope.TEAM, emptyList(), null, 50).entries.isEmpty())
        assertTrue(repository.search(ada, listOf("old"), emptyList(), 20).isEmpty())
        val expired = server.database.superuser.fetchAll("SELECT id FROM memory_entries WHERE expires_at IS NOT NULL")
            .getOrThrow().single().get("id").asString().let(Uuid::parse)
        assertFailsWith<MemoryNotFoundException> { repository.read(ada, expired) }
        assertFailsWith<MemoryNotFoundException> { repository.delete(ada, expired) { } }
    }

    @Test
    fun aDeletedOrganizationsMemoryIsPurgedOnceItsDaysAreOver() = repositoryTest { server, repository ->
        val ada = caller()
        val eve = caller(EVE, ORG_B)
        val kept = repository.create(eve, note(MemoryScope.TEAM, listOf("x")), MemoryOrigin.HUMAN, null)
        repository.create(ada, note(MemoryScope.TEAM, listOf("x")), MemoryOrigin.HUMAN, null)
        repository.create(ada, note(MemoryScope.PERSONAL), MemoryOrigin.HUMAN, null)
        val listener = server.koin.get<OrgDeletionListener>(named("memory"))
        listener.deleted(ORG_A)
        assertEquals(2, repository.index(ada, null, emptyList(), null, 50).entries.size, "Kept until the purge")
        listener.purge(ORG_A)
        assertTrue(repository.index(ada, null, emptyList(), null, 50).entries.isEmpty())
        val left = server.database.superuser.fetchAll("SELECT org_id FROM memory_tags").getOrThrow()
        assertEquals(listOf(ORG_B.toString()), left.map { it.get("org_id").asString() }, "Their tags go with them")
        assertEquals(kept.id, repository.read(eve, kept.id).id)
    }

    private companion object {
        fun note(scope: MemoryScope, tags: List<String> = emptyList()) =
            NewMemory(scope, "Deploy order", "Core first", "Merge core, then the server.", tags, emptyList(), null)
    }
}
