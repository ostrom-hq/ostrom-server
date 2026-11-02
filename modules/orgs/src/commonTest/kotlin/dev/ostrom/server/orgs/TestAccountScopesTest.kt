// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import dev.ostrom.server.identity.domain.PERSONAL_TOKEN_SCOPE_NAMES
import dev.ostrom.server.orgs.infrastructure.TEST_ACCOUNT_SCOPES
import kotlin.test.Test
import kotlin.test.assertEquals

class TestAccountScopesTest {
    @Test
    fun theQaTokenAsksOnlyForScopesOfTheCatalogue() {
        assertEquals(emptySet(), TEST_ACCOUNT_SCOPES - PERSONAL_TOKEN_SCOPE_NAMES)
    }
}
