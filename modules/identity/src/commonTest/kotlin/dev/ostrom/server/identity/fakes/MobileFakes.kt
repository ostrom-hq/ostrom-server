// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity.fakes

import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.MobileCode
import dev.ostrom.server.identity.domain.MobileCodesRepository
import dev.ostrom.server.identity.domain.MobileTokensRepository
import dev.ostrom.server.identity.domain.NotificationPreferences
import dev.ostrom.server.identity.domain.NotificationPreferencesRepository
import dev.ostrom.server.identity.domain.TokenKind
import kotlin.time.Instant
import kotlin.uuid.Uuid

class InMemoryMobileCodesRepository : MobileCodesRepository {
    val codes = mutableMapOf<String, MobileCode>()
    private val used = mutableSetOf<String>()

    override suspend fun create(code: MobileCode, codeHash: String) {
        codes[codeHash] = code
    }

    override suspend fun consume(codeHash: String, now: Instant): MobileCode? {
        val code = codes[codeHash]
        val usable = code != null && codeHash !in used && code.expiresAt > now
        if (usable) used += codeHash
        return code.takeIf { usable }
    }
}

/** The app's tokens, stored in [tokens] like every other token, with their unlock keys on the side. */
class InMemoryMobileTokensRepository(val tokens: InMemoryApiTokensRepository) : MobileTokensRepository {
    val unlockKeys = mutableMapOf<Uuid, String>()

    override suspend fun create(token: ApiToken, secretHash: String, unlockKeyHash: String) {
        tokens.create(token, secretHash)
        unlockKeys[token.id] = unlockKeyHash
    }

    override suspend fun unlock(tokenId: Uuid, userId: Uuid, unlockKeyHash: String, at: Instant): Boolean {
        val entry = tokens.tokens.entries.firstOrNull { it.value.id == tokenId } ?: return false
        val token = entry.value
        val live = token.userId == userId && token.kind == TokenKind.MOBILE && token.revokedAt == null &&
            unlockKeys[tokenId] == unlockKeyHash && (token.expiresAt?.let { it > at } ?: true)
        if (live) entry.setValue(token.copy(authenticatedAt = at))
        return live
    }
}

class InMemoryNotificationPreferencesRepository : NotificationPreferencesRepository {
    val preferences = mutableMapOf<Uuid, NotificationPreferences>()

    override suspend fun get(userId: Uuid): NotificationPreferences? = preferences[userId]

    override suspend fun put(userId: Uuid, preferences: NotificationPreferences, at: Instant) {
        this.preferences[userId] = preferences
    }
}
