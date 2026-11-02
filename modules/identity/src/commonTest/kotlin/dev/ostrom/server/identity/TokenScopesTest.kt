// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.CLI_SCOPES
import dev.ostrom.server.identity.domain.InvalidScopesException
import dev.ostrom.server.identity.domain.MAX_SCOPES
import dev.ostrom.server.identity.domain.NEVER_ON_A_TOKEN
import dev.ostrom.server.identity.domain.NOTIFICATIONS_WRITE_SCOPE
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
        assertTrue("billing:manage" !in PERSONAL_TOKEN_SCOPE_NAMES)
        assertTrue(NOTIFICATIONS_WRITE_SCOPE !in PERSONAL_TOKEN_SCOPE_NAMES)
        assertTrue("waits:ask" in PERSONAL_TOKEN_SCOPE_NAMES, "An agent's least privilege: it asks, never answers")
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
    fun aTokenMayReadTheRules() {
        // The rules routes check rules:read on a token; without it in the catalogue no token could be given it.
        assertEquals(setOf("rules:read"), validateScopes(listOf("rules:read")))
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
        val outside =
            listOf("deploy:write", "gates:answer", "agent:session", "user:*", "billing:manage", "notifications:write")
        outside.forEach {
            assertFailsWith<InvalidScopesException>(it) { validateScopes(listOf("profile:read", it)) }
        }
    }

    @Test
    fun aRefusalNamesUpToFiveScopesOutsideTheCatalogue() {
        val one = assertFailsWith<InvalidScopesException> { validateScopes(listOf("profile:read", "deploy:write")) }
        assertTrue(one.message.endsWith(" Not in it: deploy:write."), one.message)
        val many = assertFailsWith<InvalidScopesException> { validateScopes((1..7).map { "x$it:read" }) }
        assertTrue(many.message.endsWith(" Not in it: x1:read, x2:read, x3:read, x4:read, x5:read."), many.message)
        val empty = assertFailsWith<InvalidScopesException> { validateScopes(emptyList()) }
        assertTrue(empty.message.endsWith("GET /api/v1/tokens/scopes."), empty.message)
    }
}
