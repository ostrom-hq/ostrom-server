// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.presentation

import run.ostrom.server.notifications.domain.PushDevice
import run.ostrom.server.notifications.domain.PushRegistration

/** The registration the body describes. */
fun PushDeviceRequest.toRegistration(): PushRegistration = PushRegistration(pushToken, provider, platform, locale)

/** The contract's `PushDevice`: the push token stays on the server. */
fun PushDevice.toDto(): PushDeviceDto = PushDeviceDto(id.toString(), provider, platform, locale, createdAt.toString())
