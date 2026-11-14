// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.sharing.domain.AccountAction
import run.ostrom.server.sharing.domain.AccountCredentials
import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.ClaudeAccountRefusals
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.NoAccountSpendReader
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.sharing.infrastructure.ProviderQuotaHeaderParser
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// The public provider error: https://github.com/anthropics/claude-code/issues/57569.
private const val TERMS = "We've updated our Consumer Terms and Privacy Policy. You'll need to accept them in " +
    "claude.ai with the email in /status to continue."

/** Claude's terms refusal in its error envelope, with [message]. */
private fun termsRefusal(message: String = TERMS) = buildJsonObject {
    put("type", "error")
    putJsonObject("error") {
        put("type", "invalid_request_error")
        put("message", message)
    }
}.toString()

/** TeamCode #277: Claude's "accept the updated terms" refusal rests only that account, read strictly. */
class AccountActionTest {
    private val repository = InMemoryAccounts()
    private val clock = TestClock()
    private val cipher = AccountTokenCipher(ContextCipher())
    private val logged = mutableListOf<String>()

    private val pool = SharedAccountPool(
        repository,
        repository,
        tokens(repository, cipher, FakeRefresher(), clock),
        InMemorySlots(clock, repository),
        ProviderQuotaHeaderParser(),
        NoAccountSpendReader(),
        clock,
        logger = JsonLogger(LogLevel.WARN, sink = logged::add),
    )

    private suspend fun account(label: String, provider: AccountProvider = AccountProvider.CLAUDE): SharedAccount {
        val account = SharedAccount(
            Uuid.random(), Sharing.orgA, provider, label, null, "acct-$label", Sharing.memberA.memberId,
            0, AccountStatus.READY, AccountLimits(), null, clock.now(),
        )
        repository.accounts += account
        repository.envelopes[account.id] = cipher.seal(
            AccountCredentials("access-$label", "refresh-$label", clock.now() + 1.days),
            Sharing.orgA,
            account.id,
        )
        return account
    }

    private suspend fun lease(provider: AccountProvider = AccountProvider.CLAUDE) =
        assertIs<LeaseResult.Leased>(pool.lease(Sharing.orgA, Sharing.memberA.memberId, provider, null)).lease

    private fun classify(body: String) = ClaudeAccountRefusals.classify(body.encodeToByteArray())

    @Test
    fun theWholeTermsMessageInClaudesEnvelopeIsAnAccountAction() {
        assertEquals(AccountAction.ACCEPT_CLAUDE_TERMS, classify(termsRefusal()))
        val shouted = "  " + TERMS.uppercase().replace(" ", "\n") + "  "
        assertEquals(AccountAction.ACCEPT_CLAUDE_TERMS, classify(termsRefusal(shouted)), "Case and spaces aside")
        assertEquals("provider_terms_required", AccountAction.ACCEPT_CLAUDE_TERMS.code)
        assertEquals(60.seconds, AccountAction.ACCEPT_CLAUDE_TERMS.cooldown)
    }

    @Test
    fun aStatusOrAKeywordAloneNeverMakesARequestErrorTheAccounts() {
        for (message in listOf(
            "model: unknown model claude-missing",
            "prompt is too long",
            "Invalid signature in thinking block",
            "Invalid tools.0.description: You must accept the updated Terms of Service and Privacy Policy at " +
                "https://claude.ai.",
            "You must accept the tool result before continuing.",
            "You can read our updated Terms of Service and Privacy Policy at https://claude.ai.",
            "Invalid tools.0.description: $TERMS",
            "model: $TERMS",
            "$TERMS Invalid tool result.",
        )) assertNull(classify(termsRefusal(message)), message)
        for (body in listOf(
            "not json", "[]", "null", "{}", "{\"error\":null}", "{\"type\":\"error\",\"error\":[]}",
            "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":42}}",
            "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":{}}}",
            termsRefusal().replace("invalid_request_error", "authentication_error"),
            termsRefusal().replace("\"type\":\"error\",", ""),
            termsRefusal() + " ".repeat(ClaudeAccountRefusals.MAX_BYTES),
        )) assertNull(classify(body), body)
    }

    @Test
    fun onlyAClaudeAccountsBadRequestIsRead() = runTest {
        account("claude")
        account("codex", AccountProvider.CODEX)
        val claude = lease()
        val codex = lease(AccountProvider.CODEX)
        assertTrue(claude.inspects(400))
        for (status in listOf(200, 401, 403, 429, 500)) assertFalse(claude.inspects(status), "$status")
        assertFalse(codex.inspects(400))
        assertEquals(AccountAction.ACCEPT_CLAUDE_TERMS, claude.action(termsRefusal().encodeToByteArray()))
        assertNull(claude.action("{}".encodeToByteArray()))
        claude.release()
        codex.release()
    }

    @Test
    fun theAccountRestsAMinuteItsTokensKeptAndTheNextCallGoesElsewhere() = runTest {
        account("first")
        account("second")
        val refused = lease()
        refused.required(AccountAction.ACCEPT_CLAUDE_TERMS)
        refused.release()
        val stored = repository.accounts.single { it.id == refused.account.id }
        assertEquals(clock.now() + 60.seconds, stored.cooldownUntil)
        assertEquals(AccountStatus.READY, stored.status, "Nothing to reconnect or reactivate")
        val line = logged.single()
        assertTrue("provider_terms_required" in line && refused.account.id.toString() in line, line)
        assertFalse("Consumer Terms" in line, "The provider's text is never logged")
        val other = lease()
        assertTrue(other.account.id != refused.account.id, "The next call goes to the other account")
        other.release()

        // Past its rest, the account serves again with the same tokens.
        clock.advance(61.seconds)
        repository.accounts.replaceAll {
            if (it.id == other.account.id) it.copy(status = AccountStatus.DISABLED) else it
        }
        val again = lease()
        assertEquals(refused.account.id, again.account.id)
        assertEquals(refused.credentials.accessToken, again.credentials.accessToken)
        again.release()
    }

    @Test
    fun aRestNeverShortensALongerCooldown() = runTest {
        val only = account("only")
        val lease = lease()
        repository.setCooldown(Sharing.orgA, only.id, clock.now() + 5.minutes)
        lease.required(AccountAction.ACCEPT_CLAUDE_TERMS)
        lease.release()
        assertEquals(clock.now() + 5.minutes, repository.accounts.single().cooldownUntil)
    }
}
