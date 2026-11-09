// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.module

import kotlinx.serialization.Serializable

/**
 * Creates an account to drive the server in end-to-end and QA runs, through `ostrom-server seed-test-account
 * <email>`, which refuses to run unless `OSTROM_ALLOW_TEST_ACCOUNTS=true`. The identity module binds the one
 * implementation in its Koin module; until then the command says no module creates accounts.
 */
interface TestAccountSeeder {
    /**
     * Creates the account, or returns the existing one with a new token: seeding twice is harmless.
     *
     * @param email The account's e-mail address.
     *
     * @return The account and a bearer token for it.
     */
    suspend fun seed(email: String): TestAccount
}

/**
 * An account created for tests, printed as one JSON line by `ostrom-server seed-test-account`.
 *
 * @property email The account's e-mail address.
 * @property token A bearer token of the account (`Authorization: Bearer <token>`).
 * @property organizationId The account's organization, when the seeder creates one.
 */
@Serializable
data class TestAccount(
    val email: String,
    val token: String,
    val organizationId: String? = null,
)
