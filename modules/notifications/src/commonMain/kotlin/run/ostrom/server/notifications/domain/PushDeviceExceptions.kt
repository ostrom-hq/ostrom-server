// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** A registration that is not the FCM registration token and public key of an iOS or Android phone: 400. */
class InvalidPushDeviceException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "A phone registers its FCM registration token (provider fcm) with its P-256 public key, for ios or android.",
)

/** A device that is not one of the caller's phones in this organization, or no device at all: 404. */
class PushDeviceNotFoundException : OstromException(ErrorKind.NOT_FOUND, "This phone is not registered.")
