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

## One-time setup - SES (transactional email)

Email reuses the same IAM **user** as S3 - there is still exactly one long-lived access key in the
system - but it assumes its **own role**. Three things have to be true.

### 1. Create a separate SES role and point the app at it

Adding `ses:SendEmail` to the existing S3 role works and is still supported (leave `EMAIL_ROLE_ARN`
blank and SES borrows whatever credentials S3 uses). Prefer a second role. Fusing them means every
principal that can write product images can also send mail as the company: one leaked credential
then both overwrites your catalog and invoices your customers. Two roles keep those blast radii
apart, at the cost of one extra `aws iam` call per environment.

Only the ROLE differs. The user keys stay the single secret to rotate, and their only permission in
either case is `sts:AssumeRole`.

```bash
# Repeat per environment, substituting NAME/USER:
#   procurepal-ses-prod     procurepal-s3-prod
#   procurepal-ses-staging  procurepal-s3-staging
#   procurepal-ses-local    procurepal-s3-local
NAME=procurepal-ses-staging
USER=procurepal-s3-staging          # the EXISTING user from the S3 section - not a new one
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)

# 1. The role, trusting the user that already exists.
aws iam create-role --role-name "$NAME" --assume-role-policy-document '{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"AWS": "arn:aws:iam::'"$ACCOUNT"':user/'"$USER"'"},
    "Action": "sts:AssumeRole"
  }]
}'

# 2. Sending permission on the role, not on the user.
aws iam put-role-policy --role-name "$NAME" --policy-name "$NAME-send" --policy-document '{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["ses:SendEmail", "ses:SendRawEmail"],
    "Resource": "*"
  }]
}'

# 3. Let that user assume it. The user's existing inline policy only names the S3 role,
#    so this ADDS a second statement rather than replacing anything.
aws iam put-user-policy --user-name "$USER" --policy-name "$USER-assume-ses" --policy-document '{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "sts:AssumeRole",
    "Resource": "arn:aws:iam::'"$ACCOUNT"':role/'"$NAME"'"
  }]
}'
```

Then set `EMAIL_ROLE_ARN` (and optionally `EMAIL_ROLE_SESSION_NAME`) per environment - see the
table at the end of this section.

Scope `Resource` to the specific identity ARN if you would rather be strict; `*` is acceptable here
because the account sends from one domain and the sending identity is itself the real constraint -
SES refuses any `From` that is not verified regardless of what IAM allows.

**Do not chain the roles.** The application assumes the SES role directly from the user keys, never
from the already-assumed S3 session. A chained session is capped at one hour by AWS regardless of
the role's configured maximum, and it would need the S3 role's trust policy to name the SES role -
re-coupling the two roles this separation exists to keep apart. `SesClientConfig` documents this.

**If `AccessDenied` appears in the logs**, it is one of three things: the role lacks `ses:SendEmail`,
the user lacks `sts:AssumeRole` on that role, or the role's trust policy does not name the user. The
send is logged as a warning and never surfaced to a customer, which makes it a quiet failure worth
getting right the first time.

### 2. Verify a sending identity

Verify the **domain**, not individual addresses: a domain identity lets every environment send from
its own address (`no-reply@procurepal.example`, `staging-no-reply@…`) without a separate
verification each time, and domain verification is what makes DKIM available.

```bash
aws sesv2 create-email-identity --email-identity procurepal.example
aws sesv2 get-email-identity --email-identity procurepal.example \
  --query 'DkimAttributes.Tokens'
```

Publish the three CNAME records that returns, plus an SPF record and a DMARC policy. Without DKIM
and SPF, mail from a new domain lands in spam often enough that the feature will look broken rather
than misconfigured.

### 3. Leave the SES sandbox

A new SES account may only send **to** verified addresses. Everything will look correct - the
config is valid, no warning is logged, the send returns cleanly for verified test recipients - and
real customers will still get nothing. Request production access for the account before launch;
approval takes a day or so, and is the single most common reason this feature appears to work in
staging and not in production.

### Then set, per environment

| | Production service | Staging service | Local `.env` |
|---|---|---|---|
| `EMAIL_FROM_ADDRESS` | `no-reply@procurepal.example` | `staging-no-reply@procurepal.example` | blank (mail is logged, not sent) |
| `EMAIL_APP_BASE_URL` | the production frontend URL | the staging frontend URL | `http://localhost:5173` |
| `EMAIL_CONFIGURATION_SET` | `procurepal-prod` | `procurepal-staging` | blank |
| `EMAIL_ENABLED` | `true` | `false` if staging runs against a restored production database | `true` |
| `EMAIL_REPLY_TO_ADDRESS` / `EMAIL_OPERATOR_ADDRESS` | real monitored inboxes | optional | blank |
| `EMAIL_ROLE_ARN` | `arn:aws:iam::<account>:role/procurepal-ses-prod` | `…/procurepal-ses-staging` | `…/procurepal-ses-local`, or blank |
| `EMAIL_ROLE_SESSION_NAME` | `stock-bridge-api-ses-prod` | `stock-bridge-api-ses-staging` | `stock-bridge-api-ses-local` |

`EMAIL_APP_BASE_URL` must match that environment's `FRONTEND_ORIGIN` host. Getting it wrong is not
a startup failure - it produces emails whose links send customers to the wrong environment.

**`EMAIL_ENABLED=false` on a restored database.** The one operational trap in this feature. Staging
seeded from a production snapshot will, on any action that touches those orders, email the real
customers on those rows. The flag exists for exactly this and should be set *before* the restore,
not after.

### Bounces and complaints

Create a configuration set per environment and point `EMAIL_CONFIGURATION_SET` at it. Without one,
bounces and complaints are invisible to you but entirely visible to AWS, and a bounce rate above
their threshold gets the sending domain suspended - which takes down every environment at once,
since they share a domain.

```bash
aws sesv2 create-configuration-set --configuration-set-name procurepal-prod
```

Route its event destination to SNS and alarm on the bounce and complaint rates. `EmailSender` itself
still learns only whether SES *accepted* a message, which is not the same as it being delivered -
but the application now consumes the SNS side of this, and the next section is how to wire it up.

### Bounce and complaint handling (SNS -> this API)

The configuration set above tells AWS to *emit* bounce and complaint events. This section points
them at the application, which suppresses dead addresses and clears the verification flag on the
accounts holding them. Skipping it is not neutral: without it, a hard-bouncing address is mailed
again on every order, forever, and the bounce rate that gets the domain suspended is one this
application is actively contributing to.

The endpoint is `POST /api/webhooks/ses/notifications`. It is public - AWS has no bearer token of
ours - and its only authentication is the SNS message signature.

#### 1. Create a topic per environment

```bash
ENV=prod                       # prod / staging
REGION=eu-west-1               # must match the SES sending region
aws sns create-topic --name "procurepal-ses-$ENV" --region "$REGION"
```

Note the ARN it returns. It goes into `SES_SNS_TOPIC_ARN` in step 4, and that is not optional in
spirit even though it is optional in the binding - see step 4.

#### 2. Let SES publish to it

```bash
TOPIC_ARN=arn:aws:sns:eu-west-1:123456789012:procurepal-ses-prod
ACCOUNT_ID=123456789012
aws sns set-topic-attributes \
  --topic-arn "$TOPIC_ARN" \
  --attribute-name Policy \
  --attribute-value '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ses.amazonaws.com"},
      "Action": "SNS:Publish",
      "Resource": "'"$TOPIC_ARN"'",
      "Condition": {"StringEquals": {"AWS:SourceAccount": "'"$ACCOUNT_ID"'"}}
    }]
  }'
```

The `SourceAccount` condition matters: without it any AWS account's SES can publish to your topic.

#### 3. Send bounce and complaint events to it

Attach an event destination to the configuration set from the previous section. **Subscribe only
`BOUNCE` and `COMPLAINT`.** Adding `DELIVERY`, `SEND` or `OPEN` costs several notifications per
email sent and buys nothing here - the application ignores them without even writing an audit row,
which is deliberate but is still a request per event you are paying for.

```bash
aws sesv2 create-configuration-set-event-destination \
  --configuration-set-name "procurepal-$ENV" \
  --event-destination-name "procurepal-$ENV-bounces" \
  --event-destination '{
    "Enabled": true,
    "MatchingEventTypes": ["BOUNCE", "COMPLAINT"],
    "SnsDestination": {"TopicArn": "'"$TOPIC_ARN"'"}
  }'
```

The application also understands the older identity-notification dialect
(`aws ses set-identity-notification-topic`), which names the field `notificationType` instead of
`eventType`. Either wiring works; use the configuration set, because it is the one
`EMAIL_CONFIGURATION_SET` already points at.

#### 4. Set the variables, then subscribe the endpoint

Set these **before** subscribing. A subscription confirmed against a service that is not yet
configured to verify the topic ARN is a subscription you then have to think about twice.

| | Production service | Staging service | Local `.env` |
|---|---|---|---|
| `SES_SNS_TOPIC_ARN` | the prod topic ARN | the staging topic ARN | blank |
| `SES_SNS_REQUIRE_SIGNATURE` | `true` | `true` | `true` |
| `SES_SNS_REGION` | the SES region | the SES region | blank |
| `SES_SNS_COMPLAINTS_SUPPRESS_ALL_MAIL` | `true` | `true` | `true` |

**`SES_SNS_REQUIRE_SIGNATURE` must stay `true`.** This is not the same trade as
`MONNIFY_REQUIRE_WEBHOOK_SIGNATURE`, which is genuinely relaxable in sandbox because a Monnify
callback is only a trigger and the payment is re-fetched from Monnify itself. There is no equivalent
backstop here: SES has no "did that address really bounce" API, so the notification body *is* the
evidence and is acted on directly. Turned off, this endpoint is a public API for unverifying any
customer on the platform, one POST at a time. It exists for POSTing a fixture on a laptop.

**Set `SES_SNS_TOPIC_ARN` even though it is optional.** A valid SNS signature proves only that
*some* SNS topic in *some* AWS account sent the message - anyone can create a topic, hand-write a
bounce notification for any address, subscribe this endpoint to it, and have Amazon sign it with the
same certificate the application validates. Pinning the ARN is what turns "AWS sent this" into "our
topic sent this". Left blank, the application logs a warning at startup and accepts anything.

Then subscribe:

```bash
aws sns subscribe \
  --topic-arn "$TOPIC_ARN" \
  --protocol https \
  --notification-endpoint "https://api.procurepal.example/api/webhooks/ses/notifications"
```

SNS immediately POSTs a `SubscriptionConfirmation`. The application verifies its signature,
validates that the `SubscribeURL` host really is an AWS SNS host, fetches it, and the subscription
goes live - normally within a second, with `Confirmed the SES notification topic subscription` in
the log. If `SES_SNS_CONFIRM_SUBSCRIPTIONS` is `false`, the URL is logged instead and you have one
hour to open it yourself before the token expires.

#### 5. Verify it end to end

Use SES's mailbox simulator, which produces real notifications without touching a real inbox:

```bash
aws sesv2 send-email \
  --from-email-address "no-reply@procurepal.example" \
  --destination 'ToAddresses=["bounce@simulator.amazonses.com"]' \
  --configuration-set-name "procurepal-$ENV" \
  --content '{"Simple":{"Subject":{"Data":"bounce test"},"Body":{"Text":{"Data":"x"}}}}'
```

Within a few seconds:

```sql
SELECT message_type, notification_type, sub_type, signature_valid, processed, processing_note
FROM ses_notification_events ORDER BY received_at DESC LIMIT 5;

SELECT address, reason, diagnostic, created_at FROM email_suppressions ORDER BY created_at DESC LIMIT 5;
```

`complaint@simulator.amazonses.com` exercises the complaint path the same way. Note that
`ooto@simulator.amazonses.com` (out-of-office) produces a **transient** bounce and must leave both
tables' state unchanged apart from an audit row - if it suppresses anything, something is wrong.

#### What the endpoint does with each message

| Message | Effect | Answer |
|---|---|---|
| `SubscriptionConfirmation` | `SubscribeURL` host-validated, then fetched | 200 |
| `UnsubscribeConfirmation` | Logged at ERROR - see below | 200 |
| Bounce, `bounceType: Permanent` | Address suppressed; `is_email_verified` cleared on every user row holding it, in every tenant | 200 |
| Bounce, `Transient` or `Undetermined` | **Nothing.** Audit row only | 200 |
| Complaint | `receive_promotional_email` cleared on every matching user row; address also suppressed unless `SES_SNS_COMPLAINTS_SUPPRESS_ALL_MAIL=false` | 200 |
| Complaint, `complaintFeedbackType: not-spam` | Nothing - this is a reader rescuing mail from spam | 200 |
| `Delivery`, `Send`, `Open`, `Click`, ... | Ignored, no row written | 200 |
| Redelivery of a `MessageId` already processed | Nothing; audit row records the retry | 200 |
| Signature does not verify | Nothing; audit row with `signature_valid = false` | **401** |
| Valid signature, wrong `TopicArn` | Nothing; audit row | **403** |
| `SubscribeURL` / `SigningCertURL` on a non-AWS host | Refused before any fetch | **400** |
| Unparseable body | Audit row only | **400** |
| Understood but could not be applied (database down) | Nothing; SNS retries | **503** |

Everything understood is answered 2xx, including work deliberately not done, because SNS retries
non-2xx for hours and each retry costs another signature verification. A signature failure is the
one thing that must never be 2xx: if our own configuration is what is broken, 2xx would tell AWS to
discard every genuine bounce notification silently.

#### Operating it

- **`UnsubscribeConfirmation` is the alarm worth wiring up.** It means somebody detached this
  endpoint from the topic. From that moment bounce and complaint handling stops, the suppression
  list stops growing, and *nothing else in the system looks any different* while the bounce rate
  climbs at AWS towards a suspension. It is the only failure here with no symptom of its own.
- **`SELECT count(*) FROM ses_notification_events WHERE signature_valid = FALSE` should be zero.**
  A non-zero and growing count is somebody probing a public URL that disables customer email.
- **Lifting a suppression** is `DELETE FROM email_suppressions WHERE address = '…'` (lowercased -
  a CHECK constraint enforces the stored form) or `EmailSuppressionService.unsuppress`. There is no
  HTTP endpoint on purpose: one that suppresses or un-suppresses an arbitrary address is one that
  silences an arbitrary customer. Note this does **not** restore `is_email_verified` - the account
  holder must re-verify, which is the correct route back.
- **`email_suppressions.diagnostic`** holds the remote mail server's own words ("smtp; 550 5.1.1
  user unknown"). It is the first thing to read when a customer asks why their mail stopped, and the
  only field that distinguishes "this mailbox never existed" from "this domain has stopped accepting
  us", which are the same bounce and completely different problems.
- **The audit table grows with provider traffic, not with your data.** Pruning it is safe -
  `email_suppressions` references notifications by value rather than by foreign key precisely so
  that pruning can never cascade into deleting a live suppression.

### One-click unsubscribe (RFC 8058)

Two variables, and the only operational step is generating a secret and never changing it.

| | Production service | Staging service | Local `.env` |
|---|---|---|---|
| `EMAIL_UNSUBSCRIBE_SECRET` | a long random string, generated once, stored in the secret store | a *different* long random string | blank (a dev placeholder in `application-local.yml` is used) |
| `EMAIL_UNSUBSCRIBE_API_BASE_URL` | the production **API** URL | the staging **API** URL | blank |

```bash
openssl rand -base64 48
```

**`EMAIL_UNSUBSCRIBE_API_BASE_URL` is the API, not the frontend.** Every other URL in the email
configuration is a frontend route, because every link in every template is one. This one is the
exception: Gmail and Yahoo POST it from their own servers with no browser involved, so it must
resolve to `POST /api/email/unsubscribe` on this service. Pointing it at the frontend produces a
`List-Unsubscribe` header that 404s, which mail providers score as a broken unsubscribe.

**Never rotate the secret.** This is the trap, and it is the opposite of the advice for every other
secret in this document. Rotating it invalidates every unsubscribe link ProcurePal has ever mailed,
including ones in messages years old - and mail clients surface `List-Unsubscribe` from whichever
message the reader currently has open, so those old links are live. A reader who presses Unsubscribe
and gets an error presses "report spam" instead, which damages exactly the sender reputation the
configuration set above exists to protect. Treat it as permanent; it grants nothing but the ability
to clear one boolean on one address, so a leak is not an incident worth a rotation. Rotate only on a
genuine compromise, and expect complaint-rate fallout when you do. It must also be **identical
across every instance** of a service, or a link only works on the node that minted it.

**Until both are set, promotional email is dropped rather than sent.** Deliberately fail-closed;
nothing else in this document behaves that way. See `ENVIRONMENT.md` for the reasoning. Note that no
promotional sender exists in the application yet, so an environment that never sets these is fully
functional today - order receipts, verification and security mail are all unaffected. The startup
log says so explicitly rather than leaving it to be discovered.

### Email address verification

There is **no AWS setup for this** - it sends through the same SES identity as everything else. The
only operational thing to know is which variable it actually depends on, and it is not one of its
own.

**`EMAIL_APP_BASE_URL` must be set correctly, per environment.** The confirmation link is a frontend
route (`/verify-email?token=...`), built from that variable. Blank, and no link can be produced: the
welcome and invitation emails still send, but without a confirm button, and a warning is logged each
time. The consequence is larger than it sounds and is worth stating in full, because it is silent
from the customer's side:

- Every user created after `V8__email_eligibility.sql` starts unverified.
- `EmailEligibility` refuses **all** transactional mail to an unverified address.
- So with no link, order receipts, delivery updates and payment confirmations go dark for every new
  signup - permanently, and with nothing in the product explaining why.

Point it at the frontend's public origin (the same host as `FRONTEND_ORIGIN`), never at this API.
Getting it wrong in the other direction - pointing it at the API - produces links that 404 in a
customer's inbox.

**Tuning the token lifetime.** `EMAIL_VERIFICATION_TOKEN_TTL` defaults to `24h` and should be left
alone in almost all cases. Raise it only if support sees repeated "the link had already expired"
reports, and not beyond a few days: past that, the address a token was bound to has meaningfully
drifted from the address on the account, and the binding check starts refusing more links than the
expiry does - which presents to the user as the same error and is much harder to diagnose. Lowering
it below an hour is not useful; a stolen verification link only marks an address reachable that its
thief already reads, so a short window buys little and costs users who read mail on a delay.

Changing it affects **newly issued tokens only**. Links already mailed keep the expiry stamped on
them at issue, so a reduction does not retroactively kill outstanding links, and an increase does
not revive expired ones.

**Rows are never pruned.** `email_verification_tokens` grows with signups plus resends (which are
rate-limited), keeps consumed and superseded rows deliberately so "was this link already clicked"
stays answerable, and is small enough that no cleanup job exists. If it ever needs one, deleting
rows whose `expires_at` is older than a few months is safe. Deleting live ones is not.

**The resend rate limit is in-process and therefore per-instance.** `EMAIL_VERIFICATION_RESEND_LIMIT`
(default 3) and `EMAIL_VERIFICATION_RESEND_WINDOW` (default `1h`) apply independently on each
running instance, and reset on restart. Accepted deliberately - see `EmailVerificationRateLimiter`
for the reasoning and the upgrade path. If you scale to many instances and abuse appears, the fix is
to swap the limiter's map for the database count the repository already exposes; the API does not
change.

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
