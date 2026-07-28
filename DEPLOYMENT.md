# Deployment

The backend deploys via a Docker image, not a source push to Render. The flow:

```
push to main  →  GitHub Actions builds the Docker image  →  image pushed to Docker Hub
              →  GitHub Actions tells Render to deploy    →  Render pulls the image and runs it
```

See [`.github/workflows/render-deploy.yml`](.github/workflows/render-deploy.yml) for the exact
steps. It only runs for pushes to `main` that touch something other than `*.md` files.

## One-time setup

None of this happens automatically - it's infrastructure that has to exist before the workflow
can do anything.

1. **Docker Hub repository.** Create a repository (e.g. `<your-dockerhub-username>/stock-bridge-api`)
   to hold the images this workflow pushes.

2. **Render web service, pointed at that image.** Create a Render Web Service using "Existing
   Image" (not "Build from repo") pointing at `<your-dockerhub-username>/stock-bridge-api:latest`.
   Render redeploys whenever it's told to via the API (which is what the workflow's `deploy` job
   does) - it doesn't need to watch the GitHub repo itself. Leave the tag here as `:latest`:
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
   | `RENDER_SERVICE_ID` | Render service > Settings (the `srv-...` id in the URL) |
   | `RENDER_API_KEY` | Render > Account Settings > API Keys |

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
