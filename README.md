# Stock Bridge API

Multi-tenant inventory management backend for Stock Bridge. Tenants ("clients") sign up, get a
dedicated slice of data isolated by a Hibernate tenant filter, and manage products, stock
movements, users, and analytics through a JWT-secured REST API. A separate super admin surface
lets platform operators manage tenants across the whole system.

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
| `/api/superadmin/analytics` | Super admin: cross-tenant analytics |
| `/api/products` | Product CRUD, image upload, Excel bulk import/export |
| `/api/stock` | Stock in/out/adjustment movements |
| `/api/users` | Tenant user management (sub-user create/update/deactivate/password reset) |
| `/api/roles` | Assignable roles and the permissions each one grants |
| `/api/me` | Signed-in user's own profile and password |
| `/api/analytics` | Per-tenant analytics: summary, movements over time, top products, low stock |

For the full request/response shape of every endpoint, run the app and open the browsable,
always-up-to-date API docs (springdoc-openapi):

```
http://localhost:8080/swagger-ui.html
```

(raw OpenAPI JSON at `http://localhost:8080/v3/api-docs`)
