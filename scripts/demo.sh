#!/usr/bin/env bash
# Video demo: walks through the booking story one step per Enter key.
#   ./scripts/demo.sh up         fresh PostgreSQL + Redis, then run the app in the foreground
#   ./scripts/demo.sh            run the demo steps against http://localhost:8080 (second terminal)
#   ./scripts/demo.sh sql 11:00  insert an overlapping row straight into PostgreSQL (refused)
#   ./scripts/demo.sh down       remove the demo containers
# The same calls exist as a Postman collection: docs/demo/scheduler-demo.postman_collection.json
# Ports 55432 / 56379 avoid clashing with a local PostgreSQL or Redis. NO_PAUSE=1 skips the pauses.
set -euo pipefail

PG=uss-demo-pg
REDIS=uss-demo-redis
API=${API:-http://localhost:8080/api/v1}
SOLO=00000000-0000-0000-d000-000000000003
BAY=00000000-0000-0000-b000-000000000031
OIL=00000000-0000-0000-5000-000000000001
ALICE_CAR=00000000-0000-0000-e000-00000000a11c
BOB_CAR=00000000-0000-0000-e000-0000000000b0
ALICE=(-H "X-User-Id: 00000000-0000-0000-5b00-00000000a11c" -H "X-User-Roles: CUSTOMER")
BOB=(-H "X-User-Id: 00000000-0000-0000-5b00-0000000000b0" -H "X-User-Roles: CUSTOMER")
DISPATCHER=(-H "X-User-Id: 00000000-0000-0000-5b00-00000000d15b" -H "X-User-Roles: DISPATCHER"
  -H "X-Dealership-Ids: $SOLO")
DAY=$(date -v+2d +%F 2>/dev/null || date -d '+2 days' +%F)

if [[ "${1:-}" == "up" ]]; then
  docker rm -f "$PG" "$REDIS" >/dev/null 2>&1 || true
  docker run -d --name "$PG" -e POSTGRES_DB=scheduler -e POSTGRES_USER=scheduler \
    -e POSTGRES_PASSWORD=scheduler -p 55432:5432 postgres:16.4-alpine >/dev/null
  docker run -d --name "$REDIS" -p 56379:6379 redis:7.4.1-alpine >/dev/null
  until docker exec "$PG" pg_isready -U scheduler >/dev/null 2>&1; do sleep 1; done
  echo "PostgreSQL + Redis ready. Starting the app on http://localhost:8080 (Ctrl+C to stop)..."
  cd "$(dirname "$0")/.."
  APP_DATASOURCE_PRIMARY_URL=jdbc:postgresql://localhost:55432/scheduler \
  APP_DATASOURCE_REPLICA_URL=jdbc:postgresql://localhost:55432/scheduler \
  SPRING_DATA_REDIS_PORT=56379 exec ./mvnw -q spring-boot:run
fi
if [[ "${1:-}" == "down" ]]; then
  docker rm -f "$PG" "$REDIS"
  exit 0
fi
if [[ "${1:-}" == "sql" ]]; then
  # Bypass the app: a 30-minute row on Bay 1 starting at $2 (UTC, default 09:00 = Alice's booking;
  # after the dispatcher move pass 11:00). The EXCLUDE constraint refuses it.
  AT=${2:-09:00}
  SQL="INSERT INTO resource_reservation (id, dealership_id, service_bay_id, kind, start_at, end_at, reason)
VALUES (gen_random_uuid(), '$SOLO', '$BAY', 'BLOCK', '${DAY} ${AT}Z', '${DAY} ${AT}Z'::timestamptz + interval '30 minutes', 'bypass the app');"
  echo "$SQL"
  docker exec "$PG" psql -U scheduler -c "$SQL" 2>&1 || true
  exit 0
fi

step() {
  printf '\n\033[1;33m▶ %s\033[0m\n' "$1"
  [[ -n "${NO_PAUSE:-}" ]] || read -r -p "  (Enter)" _
}
show() { printf '\033[2m$ %s\033[0m\n' "$1"; }
json() { curl -s -H 'Content-Type: application/json' "$@"; }

step "1. Customer browses free times — Solo dealership: 1 bay, 1 technician, oil change 45 min"
show "GET /dealerships/{solo}/availability?serviceTypeIds={oil}&from=$DAY&to=$DAY"
json "${ALICE[@]}" "$API/dealerships/$SOLO/availability?serviceTypeIds=$OIL&from=$DAY&to=$DAY" \
  | jq '{timeZone, durationMinutes, firstStarts: .days[0].starts[0:6]}'

step "2. Alice holds 09:00 — atomic Lua script in Redis, 10 minutes"
show "POST /me/holds  (Alice, ${DAY}T09:00Z)"
HOLD_JSON=$(json "${ALICE[@]}" -X POST "$API/me/holds" -d "{\"dealershipId\":\"$SOLO\",\
\"vehicleId\":\"$ALICE_CAR\",\"serviceTypeIds\":[\"$OIL\"],\"start\":\"${DAY}T09:00:00Z\"}")
echo "$HOLD_JSON" | jq '{id, start, end, expiresAt}'
HOLD=$(echo "$HOLD_JSON" | jq -r .id)

step "3. Bob tries 09:15 (overlaps) — rejected in Redis, no database call"
show "POST /me/holds  (Bob, ${DAY}T09:15Z)"
json "${BOB[@]}" -X POST "$API/me/holds" -d "{\"dealershipId\":\"$SOLO\",\
\"vehicleId\":\"$BOB_CAR\",\"serviceTypeIds\":[\"$OIL\"],\"start\":\"${DAY}T09:15:00Z\"}" \
  | jq '{status, code, detail, alternatives}'

step "4. Alice confirms — one transaction, EXCLUDE is the final check"
BODY="{\"holdId\":\"$HOLD\",\"dealershipId\":\"$SOLO\",\"vehicleId\":\"$ALICE_CAR\",\
\"serviceTypeIds\":[\"$OIL\"],\"start\":\"${DAY}T09:00:00Z\"}"
show "POST /me/appointments  Idempotency-Key: demo-alice-1"
BOOKED=$(curl -s -D /dev/stderr "${ALICE[@]}" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-alice-1' -X POST "$API/me/appointments" -d "$BODY" \
  2> >(grep -iE '^HTTP|^etag' >&2))
echo "$BOOKED" | jq '{id, status, version, start, end}'
APPT=$(echo "$BOOKED" | jq -r .id)

step "5. Network retry: same request, same key — same appointment, no duplicate"
show "POST /me/appointments  Idempotency-Key: demo-alice-1  (again)"
curl -s -o /dev/null -D - "${ALICE[@]}" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-alice-1' -X POST "$API/me/appointments" -d "$BODY" \
  | grep -iE '^HTTP|^location|^idempotency-replayed'

step "6. Dispatcher drags the card to 11:00 (If-Match: \"0\")"
show "PATCH /dealerships/{solo}/appointments/{id}  If-Match: \"0\""
curl -s -o /dev/null -D - "${DISPATCHER[@]}" -H 'Content-Type: application/merge-patch+json' \
  -H 'If-Match: "0"' -X PATCH "$API/dealerships/$SOLO/appointments/$APPT" \
  -d "{\"start\":\"${DAY}T11:00:00Z\"}" | grep -iE '^HTTP|^etag'

step "7. A second dispatcher with a stale board drags it too — 412, the card snaps back"
show "PATCH /dealerships/{solo}/appointments/{id}  If-Match: \"0\"  (stale)"
curl -s "${DISPATCHER[@]}" -H 'Content-Type: application/merge-patch+json' -H 'If-Match: "0"' \
  -X PATCH "$API/dealerships/$SOLO/appointments/$APPT" -d "{\"start\":\"${DAY}T12:00:00Z\"}" \
  | jq '{status, code, detail}'

step "8. Bypass the app entirely: insert an overlapping row straight into PostgreSQL"
SQL="INSERT INTO resource_reservation (id, dealership_id, service_bay_id, kind, start_at, end_at, reason)
VALUES (gen_random_uuid(), '$SOLO', '$BAY', 'BLOCK', '${DAY} 11:30Z', '${DAY} 12:00Z', 'bypass the app');"
show "psql: $SQL"
docker exec "$PG" psql -U scheduler -c "$SQL" 2>&1 || true

printf '\n\033[1;32m✔ Demo complete.\033[0m\n'
