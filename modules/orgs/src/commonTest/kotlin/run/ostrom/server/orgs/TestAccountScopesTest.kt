// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs

import run.ostrom.server.identity.domain.PERSONAL_TOKEN_SCOPE_NAMES
import run.ostrom.server.orgs.infrastructure.TEST_ACCOUNT_SCOPES
import kotlin.test.Test
import kotlin.test.assertEquals

class TestAccountScopesTest {
    @Test
    fun theQaTokenAsksOnlyForScopesOfTheCatalogue() {
        assertEquals(emptySet(), TEST_ACCOUNT_SCOPES - PERSONAL_TOKEN_SCOPE_NAMES)
    }
}
