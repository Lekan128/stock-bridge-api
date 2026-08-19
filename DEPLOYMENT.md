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
   - AWS S3 (`AWS_REGION`/`AWS_S3_BUCKET_NAME`/`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`, plus
     `AWS_S3_KEY_PREFIX=prod` and `AWS_ROLE_ARN`) - see
     [One-time setup - S3 buckets](#one-time-setup---s3-buckets-product-images)
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
   - S3: the non-prod bucket with `AWS_S3_KEY_PREFIX=staging`, and staging's own access key and
     `AWS_ROLE_ARN` - never production's. Staging uploads are real S3 operations. See
     [One-time setup - S3 buckets](#one-time-setup---s3-buckets-product-images).
   - The bootstrap pairs (`SUPERADMIN_*`, `PLATFORM_OWNER_ADMIN_*`) behave exactly as described
     above - required for the first boot against an empty staging database, no-ops afterwards.
   - Monnify: leave `MONNIFY_BASE_URL` on its sandbox default. Staging must never point at
     `https://api.monnify.com`.

Then push to `staging` and watch the run: `build` then `deploy-staging`. The staging job going
green means Render accepted the deploy - confirm it actually went live in the Render dashboard.

## One-time setup - S3 buckets (product images)

Product images are uploaded through the backend (`S3ImageService`) and served straight from S3:
the URL stored on the product is `https://<bucket>.s3.<region>.amazonaws.com/<key>` and the
browser fetches it directly, so **the objects have to be publicly readable**. Nothing else in
the bucket is.

### Two buckets, not one and not three

| Bucket | Used by | Key prefix |
|---|---|---|
| `procurepal-images-prod` | production | `prod/` |
| `procurepal-images-nonprod` | staging **and** local development | `staging/`, `local/` |

Every key is `<prefix>/<tenant-id>/products/<uuid>-<filename>`, so the prefix separates
environments and the tenant id separates sellers within one.

**Why production is alone in its own bucket.** A bucket is the boundary that survives a mistake.
Staging runs code that hasn't been trusted yet and its credentials are handled far more casually,
so the two must not share a blast radius: if the staging key leaks, the worst case has to be
"staging images were deleted", not "production's catalogue was wiped".

**Why staging and local share one.** Between those two the prefix is enough. A separate third
bucket buys a second public-read policy, lifecycle rule and IAM user to keep in sync, in exchange
for isolating a set of throwaway images from another set of throwaway images. Note what the
sharing does and doesn't mean: each environment gets its **own IAM user, scoped to its own
prefix**, so local development still cannot write to (or delete) anything under `staging/`.
Sharing the bucket is not sharing the credential.

Local development doesn't have to use S3 at all - leaving the AWS variables blank degrades
gracefully (products save without an image, with a warning). Point local at the non-prod bucket
only when you're specifically working on the upload path.

### Creating them

Run once per bucket, with the AWS CLI authenticated as an admin. `REGION` must match whatever
`AWS_REGION` the services are given.

```bash
REGION=us-east-1
BUCKET=procurepal-images-prod   # then repeat the whole block with procurepal-images-nonprod

# us-east-1 is the one region create-bucket takes without a LocationConstraint.
if [ "$REGION" = us-east-1 ]; then
  aws s3api create-bucket --bucket "$BUCKET" --region "$REGION"
else
  aws s3api create-bucket --bucket "$BUCKET" --region "$REGION" \
    --create-bucket-configuration LocationConstraint="$REGION"
fi

# Public *read of objects* is required (see above) and is granted by the bucket
# policy below - which means BlockPublicPolicy has to be off, or the policy is
# refused. The two ACL switches stay ON: nothing here uses ACLs, and leaving
# them on removes the other way an object can accidentally be made public.
aws s3api put-public-access-block --bucket "$BUCKET" \
  --public-access-block-configuration \
  "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=false,RestrictPublicBuckets=false"

# Uploads that never completed are invisible in the console but still billed.
aws s3api put-bucket-lifecycle-configuration --bucket "$BUCKET" \
  --lifecycle-configuration '{"Rules":[{"ID":"abort-incomplete-multipart","Status":"Enabled","Filter":{},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":7}}]}'
```

Then the public-read policy. Scope it to the prefixes that actually hold images rather than
`/*`, so anything else that ever lands in the bucket isn't public by default:

```bash
# prod bucket
aws s3api put-bucket-policy --bucket procurepal-images-prod --policy '{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "PublicReadProductImages",
    "Effect": "Allow",
    "Principal": "*",
    "Action": "s3:GetObject",
    "Resource": "arn:aws:s3:::procurepal-images-prod/prod/*"
  }]
}'

# non-prod bucket
aws s3api put-bucket-policy --bucket procurepal-images-nonprod --policy '{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "PublicReadProductImages",
    "Effect": "Allow",
    "Principal": "*",
    "Action": "s3:GetObject",
    "Resource": [
      "arn:aws:s3:::procurepal-images-nonprod/staging/*",
      "arn:aws:s3:::procurepal-images-nonprod/local/*"
    ]
  }]
}'
```

**CORS is not needed and should not be added.** The browser only ever loads these URLs in an
`<img>` tag, which isn't a CORS-checked request, and the upload itself goes to this backend, not
to S3. A CORS rule would only become necessary if the frontend started uploading directly to S3
with presigned URLs, or reading image pixels through a `<canvas>`.

### One role per environment, assumed by one user

The application does not hold credentials that can write to S3. It holds credentials that can ask
for credentials that can, which is a meaningfully smaller thing to leak. Per environment:

- a **role** (`procurepal-s3-prod`, `procurepal-s3-staging`, `procurepal-s3-local`) whose
  permission policy grants S3 access to that environment's bucket and prefix, and whose trust
  policy names the matching user as principal;
- a **user** of the same name whose only permission is `sts:AssumeRole` on that one role — no S3
  permissions whatsoever.

The user's long-lived access key is then worth nothing on its own: presented to S3 directly it is
refused, and the only thing it can do is request a session that expires within the hour. The
prefix scoping still does the work of keeping environments apart; the role is what stops a leaked
key from being immediately useful.

```bash
# Repeat per environment, substituting NAME/BUCKET/PREFIX:
#   procurepal-s3-prod     procurepal-images-prod     prod
#   procurepal-s3-staging  procurepal-images-nonprod  staging
#   procurepal-s3-local    procurepal-images-nonprod  local
NAME=procurepal-s3-staging
BUCKET=procurepal-images-nonprod
PREFIX=staging
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)

# 1. The user. It gets no S3 permissions - only the right to assume the role.
aws iam create-user --user-name "$NAME"

# 2. The role, trusting that user and nothing else.
aws iam create-role --role-name "$NAME" \
  --assume-role-policy-document "$(jq -nc --arg principal "arn:aws:iam::$ACCOUNT:user/$NAME" '{
    Version: "2012-10-17",
    Statement: [{
      Effect: "Allow",
      Principal: { AWS: $principal },
      Action: "sts:AssumeRole"
    }]
  }')"

# 3. The S3 permissions live on the ROLE, scoped to one bucket and one prefix.
aws iam put-role-policy --role-name "$NAME" --policy-name s3-product-images \
  --policy-document "$(jq -nc --arg arn "arn:aws:s3:::$BUCKET/$PREFIX/*" '{
    Version: "2012-10-17",
    Statement: [{
      Effect: "Allow",
      Action: ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      Resource: $arn
    }]
  }')"

# 4. The user's only permission: assume that one role.
aws iam put-user-policy --user-name "$NAME" --policy-name assume-s3-role \
  --policy-document "$(jq -nc --arg arn "arn:aws:iam::$ACCOUNT:role/$NAME" '{
    Version: "2012-10-17",
    Statement: [{
      Effect: "Allow",
      Action: "sts:AssumeRole",
      Resource: $arn
    }]
  }')"

aws iam create-access-key --user-name "$NAME"
```

`create-access-key` prints the secret exactly once. Paste it straight into the Render service's
environment variables (or your local `.env`) — it is a credential and belongs in neither this
repository nor a chat window.

The role policy has no `s3:ListBucket`: the application only ever writes a key it just generated
and reads one it stored, so listing would be a capability with no caller. `s3:DeleteObject` is
included because image replacement is the obvious next change to this code, and re-issuing
permissions later is more disruptive than granting it now within a prefix that only holds images.

### The 1-hour session is not a performance concern

A role's default (and here, maximum) session duration is one hour, and `S3ClientConfig` builds an
`StsAssumeRoleCredentialsProvider` with `asyncCredentialUpdateEnabled(true)`. That provider caches
the session and refreshes it **on a background thread 5 minutes before it expires** (the SDK's
`DEFAULT_PREFETCH_TIME`), treating credentials as stale only in the final minute
(`DEFAULT_STALE_TIME`). So:

- the STS call happens roughly once every 55 minutes, not once per upload;
- no image upload ever blocks on it, because the replacement session is in place long before any
  request could find the old one expired;
- `sts:AssumeRole` is not billed.

Left on the default (`asyncCredentialUpdateEnabled` unset), the same refresh still happens on the
same schedule, but on the calling thread — one unlucky upload per hour would pay a single STS
round trip. Enabling async removes even that. Raising the role's `MaxSessionDuration` would reduce
how often the refresh happens, which is not a problem worth solving: a shorter session is the part
of this design that limits the damage of a leak.

The one thing that *would* hurt is building a fresh credentials provider per request instead of
holding the bean — that would mean an STS call on every upload. The provider is a singleton bean
precisely so it isn't.

### Then set, per environment

| | Production service | Staging service | Local `.env` |
|---|---|---|---|
| `AWS_S3_BUCKET_NAME` | `procurepal-images-prod` | `procurepal-images-nonprod` | `procurepal-images-nonprod` |
| `AWS_S3_KEY_PREFIX` | `prod` | `staging` | `local` |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | user `procurepal-s3-prod`'s | user `procurepal-s3-staging`'s | user `procurepal-s3-local`'s |
| `AWS_ROLE_ARN` | `arn:aws:iam::<account>:role/procurepal-s3-prod` | `…/procurepal-s3-staging` | `…/procurepal-s3-local` |
| `AWS_ROLE_SESSION_NAME` | `stock-bridge-api-prod` | `stock-bridge-api-staging` | `stock-bridge-api-local` |
| `AWS_REGION` | same in all three, and must match the bucket's actual region | | |

Leaving `AWS_ROLE_ARN` blank is still supported and falls back to using the access key against S3
directly — but then the key needs the S3 policy on it, and it is a long-lived credential that can
write to your bucket. Use it only if a role genuinely isn't available.

### Why there is no `application-staging.yml`

Staging runs `SPRING_PROFILES_ACTIVE=prod` (see the staging setup above) and that is the whole
design, not a shortcut taken until a staging profile exists. Every difference between the two
environments is a *value* - a bucket, a prefix, a database, an origin, a secret - and values come
from environment variables set on each Render service. `application-prod.yml` contains no values,
only the rules about which variables are mandatory. A staging profile would therefore be a
byte-for-byte copy of it, with the one guaranteed consequence that the copies eventually drift and
staging stops enforcing what production enforces - which is precisely what staging is for.

Add an `application-staging.yml` only if staging ever needs a different *rule* (not a different
value): a different `ddl-auto`, an extra Flyway location, a debug endpoint production must not
expose.

## Branching

`staging` branches from `develop` and merges into `main`:

```
develop  →  staging  →  main
(feature integration)   (deployed staging)   (production)
```

Deploys are branch-driven, so nothing ships anywhere until a merge lands. A hotfix that needs to
skip staging is an ordinary PR into `main` - just merge it back down afterwards so `staging`
doesn't sit permanently behind production.
