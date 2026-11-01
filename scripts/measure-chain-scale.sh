#!/usr/bin/env bash
# Times the section 9 targets (MULTI_BRANCH_BUILD_PLAN.md) against a running API (default :8081) for
# a company seeded by seed-chain-scale.sql. Prints each request's median of 5, in ms.
#   OWNER_EMAIL=... CLIENT_SLUG=... scripts/measure-chain-scale.sh
set -euo pipefail
API="${API:-http://localhost:8081}"
PASSWORD="${PASSWORD:-correct-horse-battery-staple}"
token=$(curl -s "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"clientIdentifier\":\"$CLIENT_SLUG\",\"username\":\"$OWNER_EMAIL\",\"password\":\"$PASSWORD\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["tokens"]["accessToken"])')
auth=(-H "Authorization: Bearer $token")
branch=$(curl -s "$API/api/branches" "${auth[@]}" | python3 -c 'import sys,json;b=json.load(sys.stdin)["branches"];print(b[5]["id"])')
main=$(curl -s "$API/api/branches" "${auth[@]}" | python3 -c 'import sys,json;b=json.load(sys.stdin)["branches"];print(b[0]["id"])')

median() { sort -n | awk '{a[NR]=$1} END {print a[int((NR+1)/2)]}'; }
time_get() {
  local label="$1" path="$2"
  local ms
  local status
  status=$(curl -s -o /dev/null -w '%{http_code}' "${auth[@]}" "$API$path")
  if [ "$status" != "200" ]; then printf '%-48s  HTTP %s\n' "$label" "$status"; return; fi
  ms=$(for i in 1 2 3 4 5; do curl -s -o /dev/null -w '%{time_total}\n' "${auth[@]}" "$API$path"; done | awk '{print int($1*1000)}' | median)
  printf '%-48s %6s ms\n' "$label" "$ms"
}

time_get "products page, at a branch"             "/api/products?size=50&branchId=$branch"
time_get "products page, at a branch, LOW"        "/api/products?size=50&branchId=$branch&stockStatus=LOW"
time_get "products page, All branches"            "/api/products?size=50"
time_get "products page, All branches, LOW"       "/api/products?size=50&stockStatus=LOW"
time_get "branch table"                           "/api/analytics/branches"
time_get "dashboard summary, at a branch"         "/api/analytics/summary?branchId=$branch"
time_get "movements page, at a branch"            "/api/stock/movements?size=20&branchId=$branch"
time_get "losses, this month"                     "/api/stock/losses"
time_get "sync snapshot page (2,000), at a branch" "/api/products/sync/snapshot?limit=2000&branch=$branch"

# A 30-line transfer, main -> the branch, timed once.
lines=$(curl -s "$API/api/products?size=30&branchId=$main&stockStatus=OK" "${auth[@]}" | python3 -c '
import sys,json
rows=json.load(sys.stdin)["content"]
print(json.dumps([{"productId":r["id"],"quantity":1} for r in rows]))')
body=$(python3 -c "import json,uuid;print(json.dumps({'id':str(uuid.uuid4()),'fromBranchId':'$main','toBranchId':'$branch','lines':$lines}))")
ms=$(curl -s -o /dev/null -w '%{time_total}' "${auth[@]}" -H 'Content-Type: application/json' -d "$body" "$API/api/stock-transfers" | awk '{print int($1*1000)}')
printf '%-48s %6s ms\n' "30-line transfer" "$ms"
