// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer

import run.ostrom.server.importer.domain.AccountImportOutcome
import run.ostrom.server.importer.domain.AccountImportRun
import run.ostrom.server.importer.domain.AccountImporter
import run.ostrom.server.importer.domain.ImportAction
import run.ostrom.server.importer.domain.ImportHandover
import run.ostrom.server.importer.domain.TeamCodeAccount
import run.ostrom.server.importer.presentation.ImportItemDto
import run.ostrom.server.importer.presentation.ImportReportDto
import run.ostrom.server.importer.presentation.TeamCodeAccountDto
import run.ostrom.server.importer.presentation.TeamCodeAssignmentDto
import run.ostrom.server.importer.presentation.TeamCodeImportRequest
import run.ostrom.server.importer.presentation.TeamCodeMcpServerDto
import run.ostrom.server.importer.presentation.TeamCodeMemberDto
import run.ostrom.server.importer.presentation.TeamCodeStdioDto
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.mcprelay.McpRelayModule
import run.ostrom.server.mcprelay.domain.ListMcpServersUseCase
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.MemberRole
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.domain.AccountCredentials
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.OAuthRefresher
import run.ostrom.server.sharing.domain.RefreshResult
import run.ostrom.server.testing.TestServer
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** The callers of the import tests: two orgs. */
object Importing {
    val orgA: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    val orgB: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    val adminA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000a1"), MemberRole.ADMIN)
    val ownerA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000d1"), MemberRole.OWNER)
    val memberA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000b1"), MemberRole.MEMBER)
    val otherA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000c1"), MemberRole.MEMBER)
    val adminB = Caller(orgB, Uuid.parse("0000000b-0000-0000-0000-0000000000a1"), MemberRole.OWNER)

    /** The modules the import writes through, and the import. */
    val modules = listOf(SecretsModule, McpRelayModule, ImportModule)

    /**
     * A typical TeamCode team: Grafana in two variants (the full one denies tools, the read-only one is an
     * allowlist), Discord per member, MySQL as a stdio command, and a shared Claude account.
     */
    @Suppress("LongParameterList") // One knob per change a test makes in TeamCode.
    fun team(
        grafanaToken: String = "g-token-1234",
        grafanaUrl: String = "https://g.example.com/mcp",
        readOnly: List<String> = listOf("query_loki_logs"),
        denied: List<String> = listOf("update_dashboard"),
        lenaDiscordToken: String? = "d-lena-token",
        discordPerMember: Boolean = true,
        extraServers: List<TeamCodeMcpServerDto> = emptyList(),
    ) = TeamCodeImportRequest(
        members = listOf(
            TeamCodeMemberDto("m1", "lena"),
            TeamCodeMemberDto("m2", "vera"),
            TeamCodeMemberDto("m3", "ghost"),
        ),
        mcpServers = listOf(
            TeamCodeMcpServerDto(
                id = "v-full",
                target = "grafana",
                variant = "full",
                url = grafanaUrl,
                credentialHeader = "Authorization",
                sharedSecret = grafanaToken,
                toolMode = "deny",
                tools = denied,
            ),
            TeamCodeMcpServerDto(
                id = "v-ro",
                target = "grafana",
                variant = "ro",
                url = "https://g.example.com/ro",
                toolMode = "allow",
                tools = readOnly,
            ),
            TeamCodeMcpServerDto(
                id = "v-discord",
                target = "discord",
                variant = "default",
                perMember = discordPerMember,
                url = "https://d.example.com/mcp",
                credentialHeader = "X-Discord-Token",
            ),
            TeamCodeMcpServerDto(
                id = "v-mysql",
                target = "mysql",
                variant = "default",
                stdio = TeamCodeStdioDto(
                    "pypi",
                    "mcp-mysql==1.2.0",
                    listOf("--read-only"),
                    mapOf(
                        "MYSQL_DSN" to "mysql-dsn-value",
                    ),
                ),
            ),
        ) + extraServers,
        assignments = listOf(
            TeamCodeAssignmentDto("m1", "v-full"),
            TeamCodeAssignmentDto("m2", "v-full", memberSecret = "stray-secret"),
            TeamCodeAssignmentDto("m1", "v-discord", memberSecret = lenaDiscordToken),
            TeamCodeAssignmentDto("m2", "v-discord"),
            TeamCodeAssignmentDto("m2", "v-ro"),
            TeamCodeAssignmentDto("m3", "v-ro"),
            TeamCodeAssignmentDto("m1", "v-mysql"),
        ),
        accounts = listOf(account("claude"), account("codex")),
        memberMap = members,
    )

    /** m1 is memberA, m2 otherA; m3 is not mapped. */
    val members = mapOf("m1" to memberA.memberId.toString(), "m2" to otherA.memberId.toString())

    private fun account(provider: String) = TeamCodeAccountDto(
        provider = provider,
        providerAccountId = "$provider-1",
        label = "Lena",
        email = "lena@example.com",
        accessToken = "access-$provider",
        refreshToken = "refresh-$provider",
        expiresAt = "2026-11-01T00:00:00Z",
    )
}

/** A line of the report, as the API says it. */
fun line(kind: String, name: String, action: ImportAction, reason: String? = null, ownership: String? = null) =
    ImportItemDto(kind, name, action.name.lowercase(), reason, ownership)

/** The sharing module's import as a fake: Claude accounts are created, the others refused. */
class FakeAccountImporter : AccountImporter {
    /** The providers imported. */
    val imported = mutableListOf<String>()

    override suspend fun begin(
        caller: Caller,
        dryRun: Boolean,
        cutover: Boolean,
        accessOnly: Boolean,
    ): AccountImportRun = object : AccountImportRun {
        // As the sharing module does: the trial leaves its accounts access-only, a normal run waiting.
        private val handover = if (accessOnly) ImportHandover.ACCESS_ONLY else ImportHandover.PENDING_HANDOVER

        override suspend fun import(account: TeamCodeAccount): AccountImportOutcome {
            if (account.provider != "claude") throw RefusedAccountException()
            if (dryRun) return AccountImportOutcome(ImportAction.PLANNED, handover)
            imported += "${caller.orgId}/${account.provider}/${account.accessToken.reveal()}"
            return AccountImportOutcome(ImportAction.CREATED, handover)
        }
    }
}

/**
 * The provider's token endpoint as a fake (a test never calls a real provider): a refresh token containing `refused`
 * is refused, one containing `offline` cannot be tested, any other renews as `renewed-` plus the old tokens. The
 * answer names the provider account `<id>` of a refresh token `refresh-<id>-token…`, and none for one containing
 * `anonymous`.
 */
class TeamCodeRefresher : OAuthRefresher {
    /** How many refreshes were asked: none while TeamCode runs. */
    var calls = 0

    /** Runs while a pair renews, given its access token: a change made meanwhile. */
    var meanwhile: suspend (String) -> Unit = {}

    override suspend fun refresh(provider: AccountProvider, credentials: AccountCredentials): RefreshResult {
        calls++
        meanwhile(credentials.accessToken)
        return when {
            "refused" in credentials.refreshToken -> RefreshResult.Revoked
            "offline" in credentials.refreshToken -> RefreshResult.Unavailable
            else -> RefreshResult.Renewed(
                AccountCredentials(
                    "renewed-${credentials.accessToken}",
                    "renewed-${credentials.refreshToken}",
                    credentials.expiresAt,
                ),
                credentials.refreshToken.takeUnless { "anonymous" in it }
                    ?.substringAfter("refresh-")
                    ?.substringBefore("-token"),
            )
        }
    }
}

/** What the fake refuses. */
class RefusedAccountException : OstromException(ErrorKind.CONFLICT, "This account is already shared.")

private val byToken = mapOf(
    "admin-a" to Importing.adminA,
    "owner-a" to Importing.ownerA,
    "member-a" to Importing.memberA,
    "admin-b" to Importing.adminB,
    "agent-a" to Importing.adminA,
    "device-a" to Importing.adminA,
    "cli-read" to Importing.adminA,
    "cli-import" to Importing.adminA,
    "service-import" to Importing.ownerA,
)

private val principals = mapOf(
    "agent-a" to AuthenticatedPrincipal("agent-a", PrincipalKind.SERVICE, setOf(RelayScopes.AGENT_SESSION)),
    "device-a" to AuthenticatedPrincipal("device-a", PrincipalKind.DEVICE, setOf(RelayScopes.ALL_USER)),
    "cli-read" to AuthenticatedPrincipal("cli-read", PrincipalKind.USER, setOf(RelayScopes.MCP_READ)),
    "cli-import" to AuthenticatedPrincipal("cli-import", PrincipalKind.USER, setOf("import:write")),
    "service-import" to AuthenticatedPrincipal(
        "000000aa-0000-0000-0000-00000000005e",
        PrincipalKind.SERVICE,
        setOf("import:write"),
    ),
    "stranger" to AuthenticatedPrincipal("stranger", PrincipalKind.USER, setOf(RelayScopes.ALL_USER)),
)

/**
 * The user behind a person's token (`owner-a`, `admin-a`…): its principal's subject.
 *
 * @param token The token.
 */
fun userOf(token: String): Uuid = Uuid.fromLongs(0xA0, byToken.keys.indexOf(token).toLong())

/** The callers by their principal's subject. */
private val bySubject = byToken.mapKeys { (token, _) -> principals[token]?.subjectId ?: "${userOf(token)}" }

/** The members of the test orgs: org A's four callers, org B's admin. */
object ImportMembers : MemberDirectory {
    private val active = with(Importing) { listOf(adminA, ownerA, memberA, otherA, adminB) }
        .map { it.orgId to "${it.memberId}" }
        .toSet()

    override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean = orgId to memberId in active
}

/**
 * The import tests' bindings: a fixed key, tokens by name, a fake token endpoint, the test orgs' members unless
 * [members] is null, an account import if given.
 */
fun importOverrides(accounts: AccountImporter? = null, members: MemberDirectory? = ImportMembers): Module = module {
    single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    single<TokenAuthenticator> {
        object : TokenAuthenticator {
            override suspend fun authenticate(token: String) = principals[token]
                ?: byToken[token]?.let {
                    AuthenticatedPrincipal("${userOf(token)}", PrincipalKind.USER, setOf(RelayScopes.ALL_USER))
                }
        }
    }
    single<CallerResolver> {
        object : CallerResolver {
            override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
                bySubject[principal.subjectId]
        }
    }
    single<OAuthRefresher> { TeamCodeRefresher() }
    members?.let { directory -> single<MemberDirectory> { directory } }
    accounts?.let { importer -> single<AccountImporter> { importer } }
}

/** The `error.type` of a refusal, the key the console reads. */
suspend fun HttpResponse.errorType(): String =
    Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("type").jsonPrimitive.content

/** Posts an import. */
suspend fun TestServer.import(
    request: TeamCodeImportRequest,
    token: String = "admin-a",
    dryRun: Boolean = false,
    cutover: Boolean = false,
    accessOnly: Boolean = false,
): HttpResponse = client.post("/api/v1/import/teamcode?dryRun=$dryRun&cutover=$cutover&accessOnly=$accessOnly") {
    bearerAuth(token)
    contentType(ContentType.Application.Json)
    setBody(request)
}

/** Posts an import that must succeed, and returns its report. */
suspend fun TestServer.report(
    request: TeamCodeImportRequest,
    token: String = "admin-a",
    dryRun: Boolean = false,
    cutover: Boolean = false,
    accessOnly: Boolean = false,
): ImportReportDto {
    val response = import(request, token, dryRun, cutover, accessOnly)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response.body()
}

/** The org's servers by name. */
suspend fun TestServer.servers(caller: Caller = Importing.adminA) =
    koin.get<ListMcpServersUseCase>()(caller).associateBy { it.name }
