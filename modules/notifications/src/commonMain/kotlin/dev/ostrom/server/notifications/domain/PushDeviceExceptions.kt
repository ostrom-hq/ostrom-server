// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException

/** A registration that is not an Expo push token of an iOS or Android phone: 400. */
class InvalidPushDeviceException :
    OstromException(ErrorKind.INVALID_REQUEST, "A phone registers an Expo push token, for ios or android.")

/** A device that is not one of the caller's phones in this organization, or no device at all: 404. */
class PushDeviceNotFoundException : OstromException(ErrorKind.NOT_FOUND, "This phone is not registered.")
