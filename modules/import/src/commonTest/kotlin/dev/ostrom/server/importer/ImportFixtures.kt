// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.importer

import dev.ostrom.server.importer.domain.AccountImporter
import dev.ostrom.server.importer.domain.ImportAction
import dev.ostrom.server.importer.presentation.ImportItemDto
import dev.ostrom.server.importer.presentation.TeamCodeAccountDto
import dev.ostrom.server.importer.presentation.TeamCodeAssignmentDto
import dev.ostrom.server.importer.presentation.TeamCodeImportRequest
import dev.ostrom.server.importer.presentation.TeamCodeMcpServerDto
import dev.ostrom.server.importer.presentation.TeamCodeMemberDto
import dev.ostrom.server.importer.presentation.TeamCodeStdioDto
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.MemberRole
import kotlin.uuid.Uuid

/** The callers of the import tests: two orgs. */
object Importing {
    val orgA: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    val orgB: Uuid = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    val adminA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000a1"), MemberRole.ADMIN)
    val memberA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000b1"), MemberRole.MEMBER)
    val otherA = Caller(orgA, Uuid.parse("0000000a-0000-0000-0000-0000000000c1"), MemberRole.MEMBER)
    val adminB = Caller(orgB, Uuid.parse("0000000b-0000-0000-0000-0000000000a1"), MemberRole.OWNER)

    /**
     * A TeamCode team like Lyes's: Grafana in two variants (the full one denies a tool, the read-only one is an
     * allowlist), Discord per member, MySQL as a stdio command, and a shared Claude account.
     */
    fun team(
        grafanaToken: String = "g-token",
        grafanaUrl: String = "https://g.example.com/mcp",
        readOnly: List<String> = listOf("query_loki_logs"),
    ) = TeamCodeImportRequest(
        members = listOf(
            TeamCodeMemberDto("m1", "lyes"),
            TeamCodeMemberDto("m2", "vivien"),
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
                tools = listOf("update_dashboard"),
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
                perMember = true,
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
                        "MYSQL_DSN" to "dsn",
                    ),
                ),
            ),
        ),
        assignments = listOf(
            TeamCodeAssignmentDto("m1", "v-full"),
            TeamCodeAssignmentDto("m2", "v-full", memberSecret = "stray"),
            TeamCodeAssignmentDto("m1", "v-discord", memberSecret = "d-lyes"),
            TeamCodeAssignmentDto("m2", "v-discord"),
            TeamCodeAssignmentDto("m2", "v-ro"),
            TeamCodeAssignmentDto("m3", "v-ro"),
            TeamCodeAssignmentDto("m1", "v-mysql"),
        ),
        accounts = listOf(account("claude"), account("codex")),
        memberMap = mapOf("m1" to memberA.memberId.toString(), "m2" to otherA.memberId.toString()),
    )

    private fun account(provider: String) = TeamCodeAccountDto(
        provider = provider,
        providerAccountId = "$provider-1",
        label = "Lyes",
        email = "lyes@example.com",
        accessToken = "access-$provider",
        refreshToken = "refresh-$provider",
        expiresAt = "2026-11-01T00:00:00Z",
    )
}

/** A line of the report, as the API says it. */
fun line(kind: String, name: String, action: ImportAction, reason: String? = null) =
    ImportItemDto(kind, name, action.name.lowercase(), reason)

/** The sharing module's import as a fake: Claude accounts are created, the others refused. */
class FakeAccountImporter : AccountImporter {
    /** The providers imported. */
    val imported = mutableListOf<String>()

    override suspend fun import(
        caller: Caller,
        account: dev.ostrom.server.importer.domain.TeamCodeAccount,
    ): ImportAction {
        if (account.provider != "claude") throw RefusedAccountException()
        imported += "${caller.orgId}/${account.provider}/${account.accessToken.reveal()}"
        return ImportAction.CREATED
    }
}

/** What the fake refuses. */
class RefusedAccountException : OstromException(ErrorKind.CONFLICT, "This account is already shared.")
