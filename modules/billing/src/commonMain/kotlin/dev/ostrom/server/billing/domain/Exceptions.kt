// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing.domain

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException

/** A plan limit was reached (402). The message says which limit, what is used, and where to upgrade. */
class PlanLimitException(message: String) : OstromException(ErrorKind.PAYMENT_REQUIRED, message)

/** The organization does not exist for the caller, or the caller may not see its billing (no leak: 404). */
class BillingAccessDeniedException : OstromException(ErrorKind.NOT_FOUND, "Organization not found.")

/** The caller is a member but not an owner or billing admin. */
class BillingAdminRequiredException :
    OstromException(ErrorKind.FORBIDDEN, "Only an owner, an admin or a billing admin can manage the subscription.")

/** Purchases are off on this instance (self-hosted: limits come from the license key). */
class BillingNotConfiguredException :
    OstromException(ErrorKind.CONFLICT, "Billing is not enabled on this instance: limits come from its license key.")

/** The plan cannot be bought online. */
class PlanNotPurchasableException(plan: Plan) : OstromException(
    ErrorKind.INVALID_REQUEST,
    if (plan == Plan.ENTERPRISE) {
        "Enterprise is sold on quote: contact sales@ostrom.run."
    } else {
        "The ${plan.key} plan cannot be bought."
    },
)

/** A checkout was asked while a subscription is running. */
class SubscriptionAlreadyActiveException : OstromException(
    ErrorKind.CONFLICT,
    "This organization already has a subscription: change its plan instead of buying a new one.",
)

/** A change was asked without a running subscription. */
class NoActiveSubscriptionException :
    OstromException(ErrorKind.CONFLICT, "This organization has no running subscription.")

/** A change the subscription's state does not allow (resume a subscription that is not ending…). */
class SubscriptionStateException(message: String) : OstromException(ErrorKind.CONFLICT, message)

/** The webhook signature is missing, wrong or too old. */
class InvalidWebhookSignatureException :
    OstromException(ErrorKind.INVALID_REQUEST, "Invalid Stripe signature.")

/** The credential is not a person's, or lacks the billing scope the route needs (S2-03). */
class BillingScopeRequiredException(scope: String) :
    OstromException(ErrorKind.FORBIDDEN, "This credential cannot use billing: it needs the $scope scope.")

/** A billing change made with a token: only a person signed in to the console makes one (403, S2-52). */
class BillingSessionRequiredException : OstromException(
    ErrorKind.FORBIDDEN,
    "This change needs a signed-in session in the console: a token cannot make it.",
)
