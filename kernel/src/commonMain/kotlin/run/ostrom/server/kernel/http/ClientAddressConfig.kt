// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.http

import run.ostrom.server.kernel.network.TrustedProxies

/**
 * Configuration of [ClientAddress].
 *
 * @property proxies The proxies whose client address header and `X-Forwarded-For` are believed; none by default.
 */
class ClientAddressConfig {
    var proxies: TrustedProxies = TrustedProxies.NONE
}
