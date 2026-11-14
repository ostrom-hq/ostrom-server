// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.domain

import run.ostrom.server.kernel.network.isPublicAddress
import run.ostrom.server.kernel.network.isReachableUrl
import run.ostrom.server.kernel.network.parseIpAddress

/**
 * Where a webhook may point, judged on its URL (the sender judges the addresses a name resolves to, at every
 * delivery). By default the public internet only (TeamCode audit L1: an admin's key must not turn webhooks into a
 * probe of the server's own network); with the operator's opt-in, loopback and private addresses too. Link-local,
 * unspecified and metadata addresses are refused either way.
 *
 * @property allowPrivate `OSTROM_WEBHOOKS_ALLOW_PRIVATE_TARGETS`.
 */
class WebhookTargets(val allowPrivate: Boolean) {
    /**
     * Whether a webhook may deliver to [url] under this policy.
     *
     * @param url The target.
     *
     * @return True when allowed.
     */
    fun allows(url: String): Boolean = isReachableUrl(url) && (allowPrivate || public(url))

    /**
     * Refuses a URL that is not an `https` URL of at most [WebhookRules.MAX_URL] characters without credentials, a
     * fragment, a control character or a space, or that names a link-local or metadata host.
     *
     * @param url The target.
     *
     * @throws InvalidWebhookException When it is not.
     */
    fun requireWellFormed(url: String) {
        val authority = url.removePrefix(HTTPS).substringBefore('/').substringBefore('?').substringBefore('#')
        val malformed = url.length > WebhookRules.MAX_URL || !url.startsWith(HTTPS) || '#' in url ||
            '@' in authority || authority.isEmpty() || url.any { it.isISOControl() || it.isWhitespace() }
        if (malformed || !isReachableUrl(url)) {
            throw InvalidWebhookException(
                "Use an https URL without credentials or a fragment, and not a link-local or cloud metadata address.",
            )
        }
    }

    private fun public(url: String): Boolean {
        val authority = url.removePrefix(HTTPS).substringBefore('/').substringBefore('?')
        val host = if (authority.startsWith("[")) {
            authority.substringBefore(']') + "]"
        } else {
            authority.substringBeforeLast(':')
        }.lowercase().trimEnd('.')
        val literal = parseIpAddress(host)
        val lastLabel = host.substringAfterLast('.')
        return when {
            literal != null -> isPublicAddress(literal)
            host == LOCALHOST || host.endsWith(".$LOCALHOST") -> false
            // No top-level domain is a number: such a host is an address in a legacy spelling (`2130706433`,
            // `0x7f.1`, `127.1`), which the system resolver would read as one.
            NUMERIC_LABEL.matches(lastLabel) -> false
            else -> true
        }
    }

    private companion object {
        const val HTTPS = "https://"
        const val LOCALHOST = "localhost"
        private val NUMERIC_LABEL = Regex("[0-9]+|0x[0-9a-f]*")
    }
}
