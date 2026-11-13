// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `GET` and `POST /api/v1/webhooks` (contract `listWebhooks`, `createWebhook`): the outgoing webhooks. */
@Serializable
@Resource("/api/v1/webhooks")
class WebhooksApi {
    /**
     * `GET`, `PUT` and `DELETE /api/v1/webhooks/{id}`.
     *
     * @property parent The collection.
     * @property id The webhook.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: WebhooksApi = WebhooksApi(), val id: String) {
        /**
         * `GET …/deliveries`: the delivery log.
         *
         * @property parent The webhook.
         * @property limit 1 to 200, 50 by default.
         */
        @Serializable
        @Resource("deliveries")
        class Deliveries(val parent: Id, val limit: Int? = null)

        /**
         * `POST …/test`: one test delivery.
         *
         * @property parent The webhook.
         */
        @Serializable
        @Resource("test")
        class Test(val parent: Id)

        /**
         * `POST …/rotate-secret`: a new secret.
         *
         * @property parent The webhook.
         */
        @Serializable
        @Resource("rotate-secret")
        class RotateSecret(val parent: Id)
    }
}
