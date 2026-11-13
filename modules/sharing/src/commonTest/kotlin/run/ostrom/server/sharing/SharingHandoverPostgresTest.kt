// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.RefreshOwnership
import run.ostrom.server.sharing.domain.ShareAccountPayload
import run.ostrom.server.sharing.domain.ShareAccountUseCase
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.sharing.domain.SharedAccountRepository
import run.ostrom.server.sharing.presentation.CapacityPoolDto
import run.ostrom.server.sharing.presentation.CapacityResponse
import run.ostrom.server.sharing.presentation.SharedAccountResponse
import run.ostrom.server.sharing.presentation.SharedAccountsResponse
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Real PostgreSQL and HTTP: TeamCode's refresh ownership. An account waiting for its handover is listed so, leased by
 * no replica, and handed over by an admin; who may call the handover routes; no handover while a refresh is in flight.
 */
class SharingHandoverPostgresTest : SharingPostgresHarness() {
    private suspend fun TestServer.imported(ownership: RefreshOwnership): Uuid = koin.get<ShareAccountUseCase>()(
        Sharing.memberA,
        ShareAccountPayload(
            AccountProvider.CLAUDE,
            "Imported Max",
            null,
            "acct-imported",
            "sk-ant-oat01-imported-access",
            "sk-ant-ort01-imported-refresh",
            Clock.System.now() + 8.hours,
            ownership = ownership,
        ),
    ).id

    private suspend fun TestServer.postAs(token: String?, path: String): HttpResponse =
        client.post(path) { token?.let { bearerAuth(it) } }

    private suspend fun TestServer.lease() = koin.get<SharedAccountPool>()
        .lease(Sharing.orgA, Sharing.otherA.memberId, AccountProvider.CLAUDE, "session-1")

    private suspend fun HttpResponse.errorType(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("type")
            .jsonPrimitive.content

    @Test
    fun aWaitingAccountIsListedSoServesNoReplicaAndServesOnceAnAdminHandsItOver() =
        serverTest(modules, overrides) { server ->
            with(server) {
                val id = imported(RefreshOwnership.PENDING_HANDOVER)
                val byAdmin = client.get(base) { bearerAuth("admin-a") }.body<SharedAccountsResponse>().items.single()
                assertEquals("pending_handover" to "pending_handover", byAdmin.status to byAdmin.refreshOwnership)
                assertEquals(false, byAdmin.paused)
                val byOther = client.get(base) { bearerAuth("other-a") }.body<SharedAccountsResponse>().items.single()
                assertEquals("pending_handover", byOther.status)
                assertEquals(null, byOther.refreshOwnership, "Only admins and its owner see who renews it")
                val capacity = client.get("/api/v1/capacity") { bearerAuth("other-a") }.body<CapacityResponse>()
                assertEquals(listOf(CapacityPoolDto("claude", 1, 0, 0)), capacity.pools)
                assertIs<LeaseResult.Refused>(lease(), "No replica leases an account its source may still renew")

                val handed = postAs("admin-a", "$base/$id/handover")
                assertEquals(HttpStatusCode.OK, handed.status, handed.bodyAsText())
                val account = handed.body<SharedAccountResponse>()
                assertEquals("ready" to "ostrom", account.status to account.refreshOwnership)
                val leased = assertIs<LeaseResult.Leased>(lease()).lease
                assertEquals("sk-ant-oat01-imported-access", leased.credentials.accessToken)
                leased.release()

                val trial = postAs("admin-a", "$base/$id/access-only").body<SharedAccountResponse>()
                assertEquals("access_only" to "access_only", trial.status to trial.refreshOwnership)
                val stored = koin.get<SharedAccountRepository>().get(Sharing.orgA, id)
                assertEquals(RefreshOwnership.ACCESS_ONLY, stored?.ownership)
            }
        }

    @Test
    fun onlyAnAdminOfTheOrgHandsOverAndNeverWhileARefreshIsInFlight() = serverTest(modules, overrides) { server ->
        with(server) {
            val id = imported(RefreshOwnership.PENDING_HANDOVER)
            for (path in listOf("$base/$id/handover", "$base/$id/access-only")) {
                assertEquals(HttpStatusCode.Unauthorized, postAs(null, path).status)
                val owner = postAs("member-a", path)
                assertEquals(HttpStatusCode.Forbidden, owner.status, "Its owner is no admin")
                assertEquals("account_handover_forbidden", owner.errorType())
                assertEquals(HttpStatusCode.NotFound, postAs("other-a", path).status, "Not theirs: as if absent")
                assertEquals(HttpStatusCode.NotFound, postAs("admin-b", path).status, "Another org's")
                assertEquals(HttpStatusCode.Forbidden, postAs("agent-a", path).status, "An agent never")
                assertEquals(HttpStatusCode.Forbidden, postAs("reader-a", path).status, "A read-only token never")
            }
            assertEquals(HttpStatusCode.NotFound, postAs("admin-a", "$base/not-a-uuid/handover").status)
            assertEquals(HttpStatusCode.NotFound, postAs("admin-a", "$base/${Uuid.random()}/handover").status)
            assertEquals(HttpStatusCode.OK, postAs("admin-a", "$base/$id/handover").status)

            // A refresh in flight (or left uncertain by a failure): only new tokens clear it.
            driver.executeInOrg(
                Sharing.orgA,
                Statement.create("UPDATE sharing_accounts SET refresh_pending = TRUE, refresh_started_at = now()"),
            ).getOrThrow()
            val refreshing = postAs("admin-a", "$base/$id/access-only")
            assertEquals(HttpStatusCode.Conflict, refreshing.status)
            assertEquals("account_refreshing", refreshing.errorType())
            val stored = koin.get<SharedAccountRepository>().get(Sharing.orgA, id)
            assertEquals(RefreshOwnership.OSTROM, stored?.ownership, "Nothing changed")
        }
    }
}
