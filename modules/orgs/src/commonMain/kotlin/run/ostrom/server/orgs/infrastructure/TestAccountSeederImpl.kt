// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.orgs.infrastructure

import run.ostrom.server.identity.domain.CreatePersonalTokenUseCase
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.SignUpGrant
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.domain.normalizeEmail
import run.ostrom.server.kernel.module.TestAccount
import run.ostrom.server.kernel.module.TestAccountSeeder
import run.ostrom.server.orgs.domain.CreateOrgUseCase
import run.ostrom.server.orgs.domain.ListMyOrgsUseCase
import kotlin.time.Duration.Companion.days

/**
 * The QA account of `ostrom-server seed-test-account` (refused unless `OSTROM_ALLOW_TEST_ACCOUNTS=true`). It goes
 * through the same use cases as a person's first e-mail sign-in, then their first team and a personal token: the
 * free-plan guard, the rate limits and the token rules all apply, and no check is skipped. Seeding again finds the
 * same person and team and issues a new token.
 *
 * @param resolveUser Finds or creates the person of a verified e-mail address.
 * @param listMyOrgs The person's teams.
 * @param createOrg Creates their team when they have none.
 * @param createToken Issues the personal token.
 */
class TestAccountSeederImpl(
    private val resolveUser: ResolveUserUseCase,
    private val listMyOrgs: ListMyOrgsUseCase,
    private val createOrg: CreateOrgUseCase,
    private val createToken: CreatePersonalTokenUseCase,
) : TestAccountSeeder {
    override suspend fun seed(email: String): TestAccount {
        val address = normalizeEmail(email)
        // The magic link proves the address the same way: an e-mail identity is its own verified subject.
        // The operator seeds it from the host: an invite-only instance's sign-up policy does not apply.
        val identity = VerifiedIdentity(IdentityProvider.EMAIL, address, address, true)
        val user = resolveUser(identity, SignUpGrant.Operator)
        val membership = listMyOrgs(user.id).firstOrNull()
            ?: createOrg(user.id, "QA ${address.substringBefore('@')}", null, operator = true)
        val issued = createToken(user.id, TEST_TOKEN_NAME, TEST_ACCOUNT_SCOPES, TEST_TOKEN_DAYS.days)
        return TestAccount(address, issued.secret, membership.org.id.toString())
    }

    private companion object {
        const val TEST_TOKEN_NAME = "qa-seed"
        const val TEST_TOKEN_DAYS = 30
    }
}

/**
 * The scopes of the QA token, as a person would tick them on a personal token: every read of the catalogue, and the
 * writes the QA journey runs with a console token. The catalogue has more scopes than a token may hold
 * ([run.ostrom.server.identity.domain.MAX_SCOPES]), so the agents' own (`gates:ask`, `waits:ask`, `mcp:use`,
 * `inference:use`), `import:write` and the package and automation writes are left out.
 */
val TEST_ACCOUNT_SCOPES: Set<String> = setOf(
    "profile:read", "orgs:read", "orgs:write", "accounts:read", "accounts:write", "artifacts:read",
    "artifacts:write", "automations:read", "billing:read", "budgets:read", "budgets:write", "cloud:read",
    "cloud:write", "gates:read", "inference:read", "inference:admin", "mcp:read", "mcp:admin", "packages:read",
    "receipts:read", "secrets:read", "secrets:write", "sessions:read", "sessions:write", "usage:read",
    "usage:write", "waits:read", "waits:write", "workflows:read", "workflows:write",
    // BENCH ONLY: the hook's gate ask, the hot path the load test drives.
    "gates:ask",
)
