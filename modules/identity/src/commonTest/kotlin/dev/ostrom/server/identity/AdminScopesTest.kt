// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.ADMIN_SCOPES
import dev.ostrom.server.identity.domain.ADMIN_TOKEN_LIFETIME
import dev.ostrom.server.identity.domain.ADMIN_TOKEN_SCOPES
import dev.ostrom.server.identity.domain.CLI_SCOPES
import dev.ostrom.server.identity.domain.InvalidScopesException
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.grantedScopes
import dev.ostrom.server.identity.domain.validateScopes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours

/** The governance scopes of `ostrom login --admin`: only its token holds them (its writes wait for the console). */
class AdminScopesTest {
    @Test
    fun onlyTheAdminTokenHoldsTheGovernanceScopesWhateverARowLists() {
        val stored = ADMIN_SCOPES + CLI_SCOPES + "orgs:write" + "user:*"
        assertEquals(ADMIN_TOKEN_SCOPES, grantedScopes(TokenKind.ADMIN, stored))
        assertEquals(CLI_SCOPES + ADMIN_SCOPES, ADMIN_TOKEN_SCOPES)
        assertEquals(CLI_SCOPES + "orgs:write", grantedScopes(TokenKind.PERSONAL, stored - "user:*"))
        assertEquals(CLI_SCOPES, grantedScopes(TokenKind.CLI, stored))
        assertEquals(8.hours, ADMIN_TOKEN_LIFETIME)
        assertEquals("osg", TokenKind.ADMIN.prefix)
    }

    @Test
    fun aPersonalTokenCannotAskForAGovernanceScope() {
        for (scope in ADMIN_SCOPES) {
            assertFailsWith<InvalidScopesException>(scope) { validateScopes(listOf("orgs:read", scope)) }
        }
    }
}
