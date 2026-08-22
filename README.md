# Procure Paddy API

Multi-tenant inventory management backend for Procure Paddy. Tenants ("clients") sign up, get a
dedicated slice of data isolated by a Hibernate tenant filter, and manage products, stock
movements, users, and analytics through a JWT-secured REST API. A separate super admin surface
lets platform operators manage tenants across the whole system.

On top of that it serves a **multi-seller marketplace**: a public catalog, cart and checkout; order
splitting when a basket spans several sellers; a seller-scoped workspace shared by ProcurePal and
third-party vendors; listing moderation; and the commission, escrow and payout machinery behind
vendor settlement. Authorization for all of it rests on two independent gates rather than one —
a permission (`@PreAuthorize`) proves the caller has the right job, and a guard
(`VendorGuard`/`PlatformOwnerGuard`, read from the database rather than from a token claim) proves
their company is the right kind. Every seller-scoped query is additionally pinned by an explicit
`seller_client_id`/owner predicate.

See the root [`APP_TOUR.md`](../APP_TOUR.md) for a feature-by-feature walkthrough and demo login
credentials, [`ENVIRONMENT.md`](../ENVIRONMENT.md) for every environment variable this service
reads, and [`DEPLOYMENT.md`](DEPLOYMENT.md) for how it gets built, published, and deployed.

## Tech stack

- Java 25, Spring Boot 4.1 (Web, Data JPA, Security, Validation, Actuator)
- PostgreSQL, Flyway for schema migrations
- JWT auth (jjwt), stateless, no server-side sessions
- Apache POI for Excel bulk import/export
- AWS S3 SDK for product image uploads (optional — see [`ENVIRONMENT.md`](../ENVIRONMENT.md))
- springdoc-openapi for browsable API docs

## Prerequisites

- Java 25 JDK
- Docker (for the local Postgres instance, and for `docker compose` runs)
- Postgres 16, if you'd rather run it yourself instead of via Docker

## Running locally

### Option A — Docker Compose (backend + Postgres, both containerized)

From the **repo root** (not this directory):

```bash
cp .env.example .env   # adjust values if needed
docker compose up --build
```

This starts Postgres and the backend together, runs migrations (including seed data — see
below) automatically on boot, and exposes the API on `http://localhost:8080`.

### Option B — `mvnw` against a local Postgres

Start just Postgres via Compose (from the repo root), then run the backend directly so you get
fast rebuild/reload cycles:

```bash
docker compose up -d stock-bridge-postgres
./mvnw spring-boot:run
```

This uses the `local` Spring profile (the default — see `application.yml`), which points at
Postgres on `localhost:5433` by default (see [`ENVIRONMENT.md`](../ENVIRONMENT.md) for why 5433
and not 5432). Override any of `POSTGRES_HOST`/`POSTGRES_PORT`/`POSTGRES_DB`/`POSTGRES_USER`/
`POSTGRES_PASSWORD` as env vars if you're pointing at a different Postgres instance.

## Migrations

Schema migrations are plain Flyway SQL files in `src/main/resources/db/migration`, applied
automatically on startup (`spring.flyway.enabled: true` in every profile).

### The vendor money tables are append-only

`vendor_ledger_entries` (V14) and `vendor_settlement_settings_changes` (V15) reject every
`UPDATE` and `DELETE` via the same trigger function — a correction is a new, opposite-signed
row naming the original, and an audit row is never edited at all. Cleaning either up (test
fixtures, or a hand-written data fix somebody consciously decided to run) needs the session
setting the trigger honours:

```sql
SET LOCAL app.ledger_maintenance = 'on';
```

Nothing in the application sets it, and nothing should. See each file's own header for the
whole design, including how the biweekly payout cadence is defined (V14) and how the escrow
maturity hold relates to it (V15).

**V16 fixed that escape hatch, and the bug is worth knowing about if you read an older header.**
As originally written it returned `COALESCE(OLD, NEW)`, and in a `BEFORE UPDATE` trigger the row
you return is the row Postgres writes — so with the flag on, an `UPDATE` succeeded, reported its
rows affected, and changed nothing at all. It behaved correctly for `DELETE`, which was the only
thing anyone had used it for, so nothing caught it for two migrations; V15 found it while writing
a backfill that would have passed on an empty developer database and then failed `SET NOT NULL` on
any database with real history. Both permitted operations now work. The refusal is unchanged:
without the flag, `UPDATE` and `DELETE` are still rejected with `restrict_violation`.

### Two clocks, and they are independent

Since V15, money accrues when the **buyer confirms receipt** and becomes **payout-eligible**
`escrow_hold_days` later (default 7). That maturity decides *whether* a ledger line may be
paid. The **14-day payout cadence** — fixed windows anchored to Monday 1 Jan 2024, 00:00
Africa/Lagos — decides *when* a run happens. They are not two halves of one schedule: a line
that matures on day 8 of a fortnight is not paid on day 8, it waits for the next run.

The hold that applies to a sale is the hold that was in force **when its buyer confirmed
it** — `vendor_ledger_entries.matures_at` is stamped once, on an append-only row — so
changing the setting affects future accruals only.

Demo/seed data (a demo tenant, three users, sample products, and stock movement history) lives
separately in `src/main/resources/db/seed`, and is **only** wired into `spring.flyway.locations`
in the `local` and `docker` profiles — `application-prod.yml` never references it, so it cannot
run against a production database. See `V9000__seed_demo_data.sql` for exactly what's seeded,
including the demo login credentials, and [`APP_TOUR.md`](../APP_TOUR.md) for how to use them.

## Tests

Most tests are Spring Boot integration tests that exercise the real HTTP + security filter chain
against a real Postgres — not mocks — so they require Postgres running locally first:

```bash
docker compose up -d stock-bridge-postgres   # from the repo root
./mvnw test
```

## API overview

Base path `/api`, grouped by area:

| Path | Covers |
|---|---|
| `/api/clients/signup` | New tenant signup |
| `/api/auth` | Tenant login/refresh |
| `/api/superadmin/auth` | Super admin login/refresh |
| `/api/superadmin/clients` | Super admin: manage tenants |
| `/api/superadmin/analytics/aggregate` | Super admin: cross-tenant STOCK MOVEMENT value per client |
| `/api/superadmin/analytics/revenue/**` | Super admin: cross-seller SALES — the marketplace total, its growth, and the per-seller breakdown. The only place total marketplace revenue exists; a tenant token, ProcurePal's included, gets a 403 |
| `/api/superadmin/settlement/settings` | Super admin: the escrow hold — how long a vendor's confirmed money waits before it is payout-eligible. `GET` reads it and its change history; `PUT /settings/escrow-hold` changes it, and requires the caller's own password re-entered plus an explicit acknowledgement. Every change is audited and emailed to all super admins. **A change applies to future accruals only** |
| `/api/superadmin/settlement/**` | Super admin: the settlement desk — preview and run the biweekly vendor payout batches, mark a batch paid or failed, reverse a delivered order's accrual, and read any vendor's statement. Marking paid is a human recording a bank transfer; there is no disbursement API |
| `/api/vendor/statement` | A seller's own account statement — sales proceeds, commission, reversals, payouts, and what is still in escrow. `/export` returns the same thing as CSV |
| `/api/marketplace/**` | The public storefront: catalog, product and seller lookup, categories, settings |
| `/api/cart`, `/api/checkout`, `/api/orders` | Buying: one shared cart per company, a server-priced quote, and order placement — a basket spanning several sellers becomes one order per seller |
| `/api/marketplace/admin/**` | ProcurePal only: its own catalog rows, the platform-wide category tree, the marketplace commercial settings, and its own sales analytics. Categories and settings are global and are the reason this is not opened to vendors |
| `/api/vendor/catalogue` | A seller's own catalogue — list/unlist (singly or in bulk), and the marketplace facets of a product it owns: brand, unit of measure, category, minimum order quantity. Seller-scoped (`requireSeller()`), so ProcurePal reaches it too; rows are pinned to the caller, so another seller's product id is a 404. Changing brand or unit of measure sends the listing back for review |
| `/api/vendor/analytics`, `/api/vendor/pickup-addresses` | A seller's own sales figures, and where its goods are collected from |
| `/api/vendor-waitlist` | Public: apply to sell. Rate limited, answers `202`, creates no account |
| `/api/superadmin/vendor-waitlist`, `/api/superadmin/vendors` | Super admin: review applications and manage the vendor roster. Approving an application, and *Add vendor*, are the only two paths that create a `VENDOR` account |
| `/api/superadmin/product-moderation` | Super admin: the listing queue — approve or reject a vendor's product, with a reason. A super-admin surface and not a marketplace-admin one, so no seller adjudicates its competitors |
| `/api/products` | Product CRUD, image upload, Excel bulk import/export. Does **not** write brand or unit of measure — those are on the marketplace-details routes above |
| `/api/stock` | Stock in/out/adjustment movements |
| `/api/company-vendors` | A buying company's own supplier directory and per-supplier purchase history. Private bookkeeping — not the marketplace vendor roster |
| `/api/delivery-addresses`, `/api/company`, `/api/notifications` | Addresses, company profile, in-app alerts |
| `/api/users` | Tenant user management (sub-user create/update/deactivate/password reset) |
| `/api/roles` | Assignable roles and the permissions each one grants |
| `/api/me` | Signed-in user's own profile and password |
| `/api/analytics` | Per-tenant analytics: summary, movements over time, top products, low stock |

## Email (Amazon SES) and webhooks

- [`SES_ENVIRONMENT_VARIABLES.md`](./SES_ENVIRONMENT_VARIABLES.md) — every email/SES variable, what
  it does, and how to obtain its value
- [`SES_LOCAL_TESTING.md`](./SES_LOCAL_TESTING.md) — send a real email from your machine while the
  AWS account is still in the SES sandbox
- [`SES_PRODUCTION_TESTING.md`](./SES_PRODUCTION_TESTING.md) — rollout order and smoke tests once
  production access is approved
- [`WEBHOOKS.md`](./WEBHOOKS.md) — every URL an outside system calls (Monnify payments, SES
  bounce/complaint notifications via SNS), and which dashboard to paste each into
- [`DEPLOYMENT.md`](./DEPLOYMENT.md) — the AWS/Render account setup behind all of the above

For the full request/response shape of every endpoint, run the app and open the browsable,
always-up-to-date API docs (springdoc-openapi):

```
http://localhost:8080/swagger-ui.html
```

(raw OpenAPI JSON at `http://localhost:8080/v3/api-docs`)
