// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.CLI_SCOPES
import dev.ostrom.server.identity.domain.InvalidScopesException
import dev.ostrom.server.identity.domain.MAX_SCOPES
import dev.ostrom.server.identity.domain.NEVER_ON_A_TOKEN
import dev.ostrom.server.identity.domain.PERSONAL_TOKEN_SCOPES
import dev.ostrom.server.identity.domain.PERSONAL_TOKEN_SCOPE_NAMES
import dev.ostrom.server.identity.domain.validateScopes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TokenScopesTest {
    private val wellFormed = Regex("[a-z][a-z0-9-]{0,31}:[a-z][a-z0-9-]{0,31}")

    @Test
    fun theCatalogueNeverListsAScopeNoTokenHolds() {
        assertTrue(PERSONAL_TOKEN_SCOPE_NAMES.none { it in NEVER_ON_A_TOKEN })
        // An agent's, and a scope that no longer allows more than billing:read (S2-52).
        assertTrue(PERSONAL_TOKEN_SCOPE_NAMES.none { it in setOf("waits:ask", "billing:manage") })
    }

    @Test
    fun everyScopeIsWellFormedUniqueAndDescribed() {
        assertEquals(PERSONAL_TOKEN_SCOPES.size, PERSONAL_TOKEN_SCOPE_NAMES.size)
        PERSONAL_TOKEN_SCOPES.forEach {
            assertTrue(wellFormed.matches(it.scope), it.scope)
            assertTrue(it.description.isNotBlank() && it.description.endsWith("."), it.scope)
        }
        assertTrue(PERSONAL_TOKEN_SCOPES.size >= MAX_SCOPES, "a token can be given any 32 of them")
    }

    @Test
    fun theCliTokenScopesAreInTheCatalogue() {
        assertTrue(PERSONAL_TOKEN_SCOPE_NAMES.containsAll(CLI_SCOPES))
    }

    @Test
    fun everyScopeOfTheCatalogueIsAccepted() {
        PERSONAL_TOKEN_SCOPE_NAMES.chunked(MAX_SCOPES).forEach { assertEquals(it.toSet(), validateScopes(it)) }
    }

    @Test
    fun aScopeOutsideTheCatalogueIsRefusedEvenWellFormed() {
        listOf("deploy:write", "gates:answer", "agent:session", "user:*", "waits:ask", "billing:manage").forEach {
            assertFailsWith<InvalidScopesException>(it) { validateScopes(listOf("profile:read", it)) }
        }
    }
}
