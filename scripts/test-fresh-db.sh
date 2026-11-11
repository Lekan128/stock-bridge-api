#!/usr/bin/env bash
# Runs the test suite against a brand-new database, then drops it.
#
# Why: the everyday local database is never emptied, so every past run's companies, products and
# payments pile up in it (tens of thousands of test companies). Tests that read across companies, and
# the scheduled jobs that sweep all of them, then slow down and fail for reasons that have nothing to
# do with the code - the "known failures" in the shared database. A fresh database is built from the
# migrations alone (which also proves they build one from nothing), holds only what this run creates,
# and anything that fails is real.
#
# Usage, from stock-bridge-api/:
#   scripts/test-fresh-db.sh                          # the whole suite
#   scripts/test-fresh-db.sh -Dtest='**/branch/**'    # any extra Maven arguments, passed through
#   KEEP_DB=1 scripts/test-fresh-db.sh                # keep the database afterwards, to look inside
#
# Needs the local Postgres container running (docker compose up -d stock-bridge-postgres, from the repo
# root) and JDK 25. Never touches the everyday database.
set -euo pipefail

CONTAINER="${POSTGRES_CONTAINER:-stock-bridge-postgres}"
DB_USER="${POSTGRES_USER:-stock_bridge}"
DB="stock_bridge_test_$(date +%Y%m%d%H%M%S)"

cd "$(dirname "$0")/.."

if ! docker exec "$CONTAINER" pg_isready -U "$DB_USER" >/dev/null 2>&1; then
  echo "Postgres isn't running. From the repo root: docker compose up -d stock-bridge-postgres" >&2
  exit 1
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" || "$("$JAVA_HOME/bin/java" -version 2>&1)" != *'version "25'* ]]; then
  if JAVA_25="$(/usr/libexec/java_home -v 25 2>/dev/null)"; then
    export JAVA_HOME="$JAVA_25"
  else
    echo "JDK 25 is needed (set JAVA_HOME)." >&2
    exit 1
  fi
fi

psql() {
  docker exec "$CONTAINER" psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 -tAqc "$1"
}

cleanup() {
  if [[ "${KEEP_DB:-}" == "1" ]]; then
    echo "Kept database $DB (drop it with: docker exec $CONTAINER dropdb -U $DB_USER $DB)"
  else
    psql "DROP DATABASE IF EXISTS \"$DB\" WITH (FORCE)" >/dev/null || true
  fi
}
trap cleanup EXIT

psql "CREATE DATABASE \"$DB\"" >/dev/null
echo "Testing against fresh database $DB"

# The local profile reads these over its .env, so the run never reaches the everyday database.
export POSTGRES_DB="$DB"
# CorsIntegrationTest expects both dev origins; the repo's .env may leave this empty.
export FRONTEND_ORIGIN="${FRONTEND_ORIGIN:-http://localhost:3000,http://localhost:5173}"

# SignupRateLimitIntegrationTest is left out of a default run: its client obeys the server's
# Retry-After and sleeps for up to an hour. Run it on its own with -Dtest=SignupRateLimitIntegrationTest.
args=("$@")
if [[ ! " ${args[*]-} " =~ -Dtest= ]]; then
  args+=("-Dtest=!SignupRateLimitIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false")
fi

./mvnw ${MVN_OFFLINE:--o} clean test "${args[@]}"
