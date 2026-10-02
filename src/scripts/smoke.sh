#!/usr/bin/env bash
# Functional smoke test: walks every endpoint and asserts status codes.
#   ./scripts/smoke.sh [BASE_URL] [ADMIN_KEY]
set -euo pipefail
B=${1:-http://localhost:8080}
ADMIN=${2:-${ADMIN_KEY:-dev-admin-key}}
RUN=$(date +%s)$RANDOM
fail=0

json() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }
expect() { # expect <name> <want> <got>
  if [[ "$2" == "$3" ]]; then echo "  ok   $1 ($3)"; else echo "  FAIL $1: want $2 got $3"; fail=1; fi
}
token() { curl -sf -XPOST "$B/auth/token" -H 'Content-Type: application/json' -d "{\"user_id\":\"$1\"}" | json '["token"]'; }
reserve() { # reserve <token> <body> -> prints "<code> <body>"
  curl -s -o /tmp/smoke.$$ -w '%{http_code}' -XPOST "$B/shows/$ID/reserve" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$2"
}

echo "health"
expect livez 200 "$(curl -s -o /dev/null -w '%{http_code}' "$B/livez")"
expect readyz 200 "$(curl -s -o /dev/null -w '%{http_code}' "$B/readyz")"

echo "shows"
expect "create without admin key" 401 "$(curl -s -o /dev/null -w '%{http_code}' -XPOST "$B/shows" -H 'Content-Type: application/json' -d '{"name":"x","seats":["A1"],"price_paise":1}')"
expect "float price rejected" 400 "$(curl -s -o /dev/null -w '%{http_code}' -XPOST "$B/shows" -H "X-Admin-Key: $ADMIN" -H 'Content-Type: application/json' -d '{"name":"x","seats":["A1"],"price_paise":250.5}')"
ID=$(curl -sf -XPOST "$B/shows" -H "X-Admin-Key: $ADMIN" -H 'Content-Type: application/json' \
  -d '{"name":"smoke","seats":["A1","A2","A3","A4","A5","A6"],"price_paise":25000}' | json '["id"]')
echo "  show $ID"

TA=$(token "alice-$RUN"); TB=$(token "bob-$RUN")

echo "reserve"
code=$(reserve "$TA" '{"seats":["A1"],"idempotency_key":"k1","user_id":"bob-'$RUN'"}'); expect "alice A1" 201 "$code"
RID=$(json '["reservation_id"]' < /tmp/smoke.$$)
expect "identity from token, not body" "alice-$RUN" "$(json '["user_id"]' < /tmp/smoke.$$)"
expect "amount in paise" 25000 "$(json '["amount_paise"]' < /tmp/smoke.$$)"
code=$(reserve "$TA" '{"seats":["A1"],"idempotency_key":"k1"}'); expect "replay same key" 200 "$code"
expect "replay returns original" "$RID" "$(json '["reservation_id"]' < /tmp/smoke.$$)"
code=$(reserve "$TA" '{"seats":["A2"],"idempotency_key":"k1"}'); expect "same key, different seats" 409 "$code"
code=$(reserve "$TB" '{"seats":["A1"],"idempotency_key":"b1"}'); expect "bob A1 taken" 409 "$code"
code=$(reserve "$TB" '{"seats":["A2","A1"],"idempotency_key":"b2"}'); expect "all-or-nothing partial" 409 "$code"
code=$(reserve "$TB" '{"seats":["A2","A3","A4","A5","A6"],"idempotency_key":"b3"}'); expect "over per-user limit" 409 "$code"
code=$(reserve "$TB" '{"seats":["Z9"],"idempotency_key":"b4"}'); expect "unknown seat" 422 "$code"
code=$(reserve "$TB" '{"seats":["A3"]}'); expect "missing idempotency key" 400 "$code"
expect "no token" 401 "$(curl -s -o /dev/null -w '%{http_code}' -XPOST "$B/shows/$ID/reserve" -H 'Content-Type: application/json' -d '{"seats":["A3"],"idempotency_key":"x"}')"
expect "forged token" 401 "$(curl -s -o /dev/null -w '%{http_code}' -XPOST "$B/shows/$ID/reserve" -H 'Authorization: Bearer abc.def.ghi' -H 'Content-Type: application/json' -d '{"seats":["A3"],"idempotency_key":"x"}')"
A2=$(curl -s "$B/shows/$ID" | json '["seats"][1]["status"]'); expect "partial request left A2 untouched" available "$A2"

echo "cancel"
expect "bob cancels alice's" 404 "$(curl -s -o /dev/null -w '%{http_code}' -XPOST "$B/reservations/$RID/cancel" -H "Authorization: Bearer $TB")"
expect "alice cancels" 200 "$(curl -s -o /dev/null -w '%{http_code}' -XPOST "$B/reservations/$RID/cancel" -H "Authorization: Bearer $TA")"
expect "cancel is idempotent" 200 "$(curl -s -o /dev/null -w '%{http_code}' -XPOST "$B/reservations/$RID/cancel" -H "Authorization: Bearer $TA")"
code=$(reserve "$TB" '{"seats":["A1"],"idempotency_key":"b5"}'); expect "released seat re-bookable" 201 "$code"

echo "state"
STATE=$(curl -s "$B/shows/$ID")
expect "reconciled" True "$(echo "$STATE" | json '["reconciled"]')"
expect "confirmed count" 1 "$(echo "$STATE" | json '["counts"]["confirmed"]')"
rm -f /tmp/smoke.$$
[[ $fail == 0 ]] && echo "SMOKE PASSED" || { echo "SMOKE FAILED"; exit 1; }
