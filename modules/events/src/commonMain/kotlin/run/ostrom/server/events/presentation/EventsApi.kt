// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/events` (contract `streamEvents`): the organization's live event feed.
 *
 * @property after The sequence of the last event handled; `Last-Event-ID` wins over it.
 * @property types Comma-separated event types to keep.
 */
@Serializable
@Resource("/api/v1/events")
class EventsApi(val after: String? = null, val types: String? = null)
