# Stripe provisioning

`provision.py` brings a Stripe account to the state of `catalog.json`: Stripe Tax (head office, exclusive prices,
SaaS tax code `txcd_10103001`, FR registration), the meter of active people, the products and prices, the customer
portal and the webhook endpoint. It is idempotent: a rerun changes only what differs and never deletes.

```sh
python3 ops/stripe/provision.py --env test --dry-run   # what would change
python3 ops/stripe/provision.py --env test             # apply (key: ~/.config/ostrom/stripe-test.key)
python3 ops/stripe/provision.py --env test --check     # exit 1 on drift (CI: stripe-test-drift)
python3 ops/stripe/provision.py --env live             # at launch, with ~/.config/ostrom/stripe-live.key
```

The webhook signing secret is shown by Stripe only when the endpoint is created: the script writes it to
`~/.config/ostrom/stripe-<env>-webhook.secret` (0600), never on the terminal. A key of the other environment is
refused (`sk_live_` in a `--env test` run).

## The catalog

| Lookup key | Price | Billing |
| --- | --- | --- |
| `ostrom_team_monthly` | 35 USD per active person | monthly, in arrears, meter `ostrom_active_person` (sum) |
| `ostrom_team_yearly` | 336 USD per person (35 × 12 × 0.8) | yearly, in advance, quantity = committed people |
| `ostrom_business_monthly` | 65 USD per active person | monthly, in arrears, same meter |
| `ostrom_business_yearly` | 624 USD per person (65 × 12 × 0.8) | yearly, in advance |

Free has no Stripe object (an organization without a subscription is on Free). Enterprise is a product without a
price: quotes are invoiced by hand. The server finds prices by lookup key, never by id, so switching from test to live
is only a matter of keys.

## Settings Stripe does not expose in its API (dashboard, once per account)

- Billing → Revenue recovery: Smart Retries (8 tries over 2 weeks), then **cancel the subscription**; e-mails for
  failed payments and expiring cards ON; "Send a Stripe-hosted link for customers to update payment" ON.
- Billing → Subscriptions and e-mails: e-mail finalized invoices and receipts to customers ON.
- Settings → Branding: logo, colors, `ostrom.run`; Settings → Public details: support e-mail and URL.
