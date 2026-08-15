# Deployment

The backend deploys via a Docker image, not a source push to Render. The flow:

```
push to main     →  GitHub Actions builds the Docker image  →  image pushed to Docker Hub
                 →  GitHub Actions tells Render to deploy    →  Render pulls the image and runs it
push to staging  →  same build  →  same Docker Hub repository, different tag
                 →  staging service's deploy hook           →  staging Render service
```

See [`.github/workflows/render-deploy.yml`](.github/workflows/render-deploy.yml) for the exact
steps. It runs for pushes to `main` and `staging` that touch something other than `*.md` files.

## Environments

| | Production | Staging |
|---|---|---|
| Branch | `main` | `staging` |
| Render service | production service | a second, separate service |
| Image tag the service is configured with | `:latest` | `:staging` |
| Tag each deploy actually pins | `:<commit-sha>` | `:<commit-sha>` |
| How the deploy is triggered | Render API (`POST /v1/services/…/deploys`) | the service's deploy hook, with `imgURL` |
| Does the workflow wait for it? | Yes - a failed deploy fails the job | No - green means "Render accepted it" |
| Database | production Postgres | its own Postgres, never production's |

Both environments build the *same* image the same way and both pin an immutable
`:<commit-sha>` tag rather than a moving one, so what runs on staging is bit-for-bit what
would run in production. Only the trigger mechanism differs.

The two moving tags exist for manual redeploys from the Render dashboard: a service redeployed
by hand uses the tag configured in its own settings, so production falls back to `:latest` and
staging to `:staging` rather than one environment silently pulling the other's build.

### Why staging uses a deploy hook and production doesn't

A deploy hook is a single URL with its own embedded key, scoped to one service. Staging is the
environment that gets pushed to constantly, and giving that path an account-wide `RENDER_API_KEY`
buys nothing - so it gets the hook, and the hook alone is the whole credential.

The cost is that the hook returns as soon as Render *queues* the deploy. The workflow can't poll
it to completion without an API key, so a green `deploy-staging` job means the request was
accepted, not that staging is live - check the Render dashboard for the outcome. Production is
worth the stricter treatment, so it keeps the API call and polls until the deploy reaches a
terminal status.

Appending `imgURL` to the hook URL is what makes it pin an exact tag; without it, the hook just
redeploys whatever tag the service already points at. Render requires every component of
`imgURL` *except* the tag to match the service's configured image, so the staging service must be
configured against the same Docker Hub repository as production.

## One-time setup - production

None of this happens automatically - it's infrastructure that has to exist before the workflow
can do anything.

1. **Docker Hub repository.** Create a repository (e.g. `<your-dockerhub-username>/stock-bridge-api`)
   to hold the images this workflow pushes.

2. **Render web service, pointed at that image.** Create a Render Web Service using "Existing
   Image" (not "Build from repo") pointing at `<your-dockerhub-username>/stock-bridge-api:latest`.
   Render redeploys whenever it's told to via the API (which is what the workflow's
   `deploy-production` job does) - it doesn't need to watch the GitHub repo itself. Leave the
   tag here as `:latest`:
   the workflow overrides it per-deploy with the exact `:<commit-sha>` it just built, but that
   override applies to that one deploy only and never rewrites this setting - so `:latest` is
   what a *manual* redeploy from the Render dashboard falls back to. Render requires the
   registry, repository and image name configured here to match the image the workflow pushes;
   only the tag may differ.

3. **Four GitHub Actions secrets**, on the `stock-bridge-api` repo (Settings > Secrets and
   variables > Actions > New repository secret):

   | Secret | Where to get it |
   |---|---|
   | `DOCKERHUB_USERNAME` | Your Docker Hub username/namespace |
   | `DOCKERHUB_TOKEN` | Docker Hub > Account Settings > Security > New Access Token |
   | `RENDER_SERVICE_ID` | Production Render service > Settings (the `srv-...` id in the URL) |
   | `RENDER_API_KEY` | Render > Account Settings > API Keys |

   The first two are shared with staging; the last two are production-only.

4. **Application-level environment variables, set directly on the Render service** (Render
   dashboard > service > Environment) - these are config for the running app, not for this
   workflow, so they don't go in GitHub secrets. See [`../ENVIRONMENT.md`](../ENVIRONMENT.md) for
   what each one does and whether it's required:
   - Database connection (`POSTGRES_HOST`/`PORT`/`DB`/`USER`/`PASSWORD`, pointed at whatever
     Postgres the production deploy uses - not the local Docker Compose one)
   - `JWT_SECRET`
   - AWS S3 credentials (`AWS_REGION`/`AWS_S3_BUCKET_NAME`/`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`)
   - Super admin bootstrap (`SUPERADMIN_USERNAME`/`SUPERADMIN_PASSWORD`)
   - Platform owner bootstrap (`PLATFORM_OWNER_ADMIN_EMAIL`/`PLATFORM_OWNER_ADMIN_PASSWORD`, plus
     the optional `PLATFORM_OWNER_NAME`/`SLUG`/`ADMIN_USERNAME`/`PHONE`/`PAYMENT_TERMS`)
   - `FRONTEND_ORIGIN` (the deployed frontend's actual URL)
   - `SPRING_PROFILES_ACTIVE=prod`

   `stock-bridge-api/.env.example` lists the same variables as a template if it's easier to work
   from a file than the table in `ENVIRONMENT.md`.

   **The two bootstrap pairs are the ones to get right on the very first deploy.** Neither has a
   default in `prod` — that is intentional, so a forgotten variable creates nothing rather than
   creating something with a guessable password — which means a production database brought up
   without them has no way to sign in as the platform operator and no marketplace seller. Both
   bootstraps are no-ops on every boot after the first, so the usual sequence is: set all four,
   deploy, log in, rotate both passwords in the app, then delete the four variables from Render.
   Redeploying afterwards without them is expected and harmless.

Once all of the above exists, every push to `main` (that isn't docs-only) rebuilds the image,
pushes both a `latest` tag and a `:<git-sha>` tag (the SHA tag is what makes it possible to tell
which commit is actually running, or to roll back Render to a specific prior image), and triggers
a Render deploy that the workflow waits on - a red workflow run means the deploy did not
succeed, not just that the trigger request was sent.

## One-time setup - staging

Staging reuses the Docker Hub repository and credentials above. What it needs of its own:

1. **A second Render web service.** Same "Existing Image" setup as production, pointed at
   `<your-dockerhub-username>/stock-bridge-api:staging` - the same repository as production,
   only a different tag. Render rejects a deploy whose `imgURL` differs from the service's
   configured image in anything but the tag, so this must not be a different Docker Hub repo.

2. **Its own database.** Point `POSTGRES_*` at a separate Postgres instance. This is the part
   worth being deliberate about: the image is identical to production's, so the *only* thing
   standing between a staging deploy and production data is this configuration. Schema changes
   run automatically on boot, and staging exists precisely to run code that hasn't been trusted
   yet.

3. **One more GitHub Actions secret:**

   | Secret | Where to get it |
   |---|---|
   | `RENDER_STAGING_DEPLOY_HOOK` | Staging Render service > Settings > Deploy Hook |

   Copy the whole URL, including its `?key=...` - that key is the credential, so the entire URL
   is the secret. The workflow appends `&imgURL=...` to it.

4. **The same application-level environment variables as production** (step 4 above), set on the
   staging service, with staging values:
   - `SPRING_PROFILES_ACTIVE=prod` - yes, `prod`. This is the profile that enforces real config
     (no default JWT secret, no wildcard CORS); staging is only worth having if it runs
     production's configuration rules.
   - `FRONTEND_ORIGIN` = the Netlify staging URL (`https://staging--<site-name>.netlify.app`),
     not the production frontend. See
     [`../stock-bridge-ui/DEPLOYMENT.md`](../stock-bridge-ui/DEPLOYMENT.md).
   - A **different** `JWT_SECRET` from production, so a token minted by one environment is not
     accepted by the other.
   - S3: a separate bucket, or at least a separate prefix. Staging uploads and deletes are real
     S3 operations.
   - The bootstrap pairs (`SUPERADMIN_*`, `PLATFORM_OWNER_ADMIN_*`) behave exactly as described
     above - required for the first boot against an empty staging database, no-ops afterwards.
   - Monnify: leave `MONNIFY_BASE_URL` on its sandbox default. Staging must never point at
     `https://api.monnify.com`.

Then push to `staging` and watch the run: `build` then `deploy-staging`. The staging job going
green means Render accepted the deploy - confirm it actually went live in the Render dashboard.

## Branching

`staging` branches from `develop` and merges into `main`:

```
develop  →  staging  →  main
(feature integration)   (deployed staging)   (production)
```

Deploys are branch-driven, so nothing ships anywhere until a merge lands. A hotfix that needs to
skip staging is an ordinary PR into `main` - just merge it back down afterwards so `staging`
doesn't sit permanently behind production.
