// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.instance.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * This Ostrom instance: one row, created at the first start and never changed.
 *
 * @property id The stable id of the instance, which receipts and packages name as their issuer.
 * @property createdAt When the instance first started.
 */
data class Instance(
    val id: Uuid,
    val createdAt: Instant,
)

/**
 * A signing key the instance has used. Keys are never forgotten: a receipt signed before a key change must still
 * verify.
 *
 * @property keyId The key id (16 hex characters).
 * @property algorithm The algorithm, `ed25519`.
 * @property publicKeyPem The public key in PEM (SPKI).
 * @property firstSeenAt When the instance first started with this key.
 */
data class SigningKey(
    val keyId: String,
    val algorithm: String,
    val publicKeyPem: String,
    val firstSeenAt: Instant,
)
