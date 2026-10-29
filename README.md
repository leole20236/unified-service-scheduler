# Unified Service Scheduler

Backend for booking car service appointments at dealership workshops (Keyloop technical
assessment, Scenario A).

Every booking reserves **one service bay and one qualified technician for the whole job**, and
neither can ever be double-booked, even when many requests race for the same slot.

- **Built here (MVP backend):** REST API, PostgreSQL schema, Redis slot holds and cache, the
  transactional outbox (events are written in the same transaction), and unit, integration and
  concurrency tests.
- **Designed only:** UI (mockups), API gateway + JWT login, multi-region, Kubernetes
  autoscaling, the outbox relay + broker (Kafka / SNS-SQS). See
  [`docs/system-design`](docs/system-design/01-high-level-design.md).

**Stack:** Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Redis 7 · Flyway · Testcontainers · Maven

| Start here | |
|---|---|
| Video walkthrough (9 min, English subtitles) | https://youtu.be/vMx6EF7OorQ |
| System design: high level | [`docs/system-design/01-high-level-design.md`](docs/system-design/01-high-level-design.md) |
| System design: detailed (schema, APIs, concurrency) | [`docs/system-design/02-detailed-design.md`](docs/system-design/02-detailed-design.md) |
| Diagrams | [`docs/diagrams`](docs/diagrams) |
| How I worked with AI | [`docs/ai-collaboration/decision-log.md`](docs/ai-collaboration/decision-log.md) |

---

## 1. Run it locally

### Prerequisites

| Tool | Version | Check |
|---|---|---|
| JDK | 21 | `java -version` |
| Docker | running | `docker ps` |
| curl + jq | optional, for the demo script | `jq --version` |

Maven is not needed: the project ships the Maven wrapper (`./mvnw`).

### Quick start (one command)

```bash
./scripts/demo.sh up
```

This starts a fresh PostgreSQL (`localhost:55432`) and Redis (`localhost:56379`) in Docker,
then runs the app on **http://localhost:8080** in the foreground. Flyway creates the schema and
loads the seed data on start-up. The first run downloads Maven dependencies and takes a few
minutes. Ports 55432 / 56379 avoid clashing with a PostgreSQL or Redis you already run.

Check it is up:

```bash
curl -s localhost:8080/actuator/health        # {"status":"UP"}
```

| URL | What |
|---|---|
| http://localhost:8080/swagger-ui.html | Swagger UI (try every endpoint) |
| http://localhost:8080/v3/api-docs | OpenAPI JSON |
| http://localhost:8080/actuator/health | Health |
| http://localhost:8080/actuator/prometheus | Metrics |

Stop with `Ctrl+C`, then remove the containers:

```bash
./scripts/demo.sh down
```

### Run against your own PostgreSQL / Redis

The app reads its connections from `src/main/resources/application.yml`. Override them with
environment variables:

```bash
APP_DATASOURCE_PRIMARY_URL=jdbc:postgresql://localhost:5432/scheduler \
APP_DATASOURCE_REPLICA_URL=jdbc:postgresql://localhost:5432/scheduler \
APP_DATASOURCE_PRIMARY_USERNAME=scheduler APP_DATASOURCE_PRIMARY_PASSWORD=scheduler \
APP_DATASOURCE_REPLICA_USERNAME=scheduler APP_DATASOURCE_REPLICA_PASSWORD=scheduler \
SPRING_DATA_REDIS_HOST=localhost SPRING_DATA_REDIS_PORT=6379 \
./mvnw spring-boot:run
```

Reads marked read-only go to the **replica** URL; locally, point both URLs at the same
database. The database needs PostgreSQL 16 (it uses `btree_gist` for the exclusion
constraint; the migration creates the extension).

### Build a jar

```bash
./mvnw -DskipUTs=true package      # skip tests; integration tests only run on verify
java -jar target/unified-service-scheduler-0.1.0-SNAPSHOT.jar
```

---

## 2. Try it

### Who you are: identity headers

In production an API gateway validates the JWT and adds these headers. Locally **you send
them yourself**. A request without `X-User-Id` and `X-User-Roles` gets `401`.

| Header | Example | Needed for |
|---|---|---|
| `X-User-Id` | `00000000-0000-0000-5b00-00000000a11c` | every `/api/**` call |
| `X-User-Roles` | `CUSTOMER` or `DISPATCHER` | every `/api/**` call |
| `X-Dealership-Ids` | `00000000-0000-0000-d000-000000000003` | dispatcher calls (dealerships they may act for) |

### Seed data

| What | Id | Notes |
|---|---|---|
| Dealership **Riverside** | `00000000-0000-0000-d000-000000000001` | Europe/London, 3 bays |
| Dealership **Hudson** | `00000000-0000-0000-d000-000000000002` | America/New_York, 2 bays |
| Dealership **Solo** | `00000000-0000-0000-d000-000000000003` | Europe/London, 1 bay, 1 technician: easiest to see conflicts |
| Service **Oil change** (45 min) | `00000000-0000-0000-5000-000000000001` | skill OIL |
| Service **Brake inspection** (75 min) | `00000000-0000-0000-5000-000000000002` | skill BRAKES |
| Service **Diagnostic** (60 min) | `00000000-0000-0000-5000-000000000003` | skill DIAG |
| Customer **Alice** (user id) | `00000000-0000-0000-5b00-00000000a11c` | vehicle `00000000-0000-0000-e000-00000000a11c` |
| Customer **Bob** (user id) | `00000000-0000-0000-5b00-0000000000b0` | vehicle `00000000-0000-0000-e000-0000000000b0` |
| Dispatcher (any user id) | e.g. `00000000-0000-0000-5b00-00000000d15b` | role `DISPATCHER` + `X-Dealership-Ids` |

### Book a service with curl

```bash
API=http://localhost:8080/api/v1
SOLO=00000000-0000-0000-d000-000000000003
OIL=00000000-0000-0000-5000-000000000001
DAY=$(date -v+2d +%F 2>/dev/null || date -d '+2 days' +%F)
ALICE=(-H "X-User-Id: 00000000-0000-0000-5b00-00000000a11c" -H "X-User-Roles: CUSTOMER")

# 1. Free start times
curl -s "${ALICE[@]}" "$API/dealerships/$SOLO/availability?serviceTypeIds=$OIL&from=$DAY&to=$DAY" | jq

# 2. Hold 09:00 for 10 minutes
HOLD=$(curl -s "${ALICE[@]}" -H 'Content-Type: application/json' -X POST "$API/me/holds" -d "{
  \"dealershipId\":\"$SOLO\",\"vehicleId\":\"00000000-0000-0000-e000-00000000a11c\",
  \"serviceTypeIds\":[\"$OIL\"],\"start\":\"${DAY}T09:00:00Z\"}" | jq -r .id)

# 3. Confirm (safe to retry with the same Idempotency-Key)
curl -s -i "${ALICE[@]}" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -X POST "$API/me/appointments" -d "{\"holdId\":\"$HOLD\",\"dealershipId\":\"$SOLO\",
  \"vehicleId\":\"00000000-0000-0000-e000-00000000a11c\",\"serviceTypeIds\":[\"$OIL\"],
  \"start\":\"${DAY}T09:00:00Z\"}"
```

### Guided demo

| How | Command |
|---|---|
| Terminal walkthrough (8 steps, Enter between steps) | `./scripts/demo.sh` in a second terminal |
| Postman | import [`docs/demo/scheduler-demo.postman_collection.json`](docs/demo/scheduler-demo.postman_collection.json), run requests 1–7 in order |
| Prove the database refuses an overlap, bypassing the app | `./scripts/demo.sh sql 09:00` |

The demo shows a hold, a competing hold refused with `409` + alternatives, a confirmed booking,
an idempotent replay, a dispatcher move with `If-Match`, a stale move refused with `412`, and
PostgreSQL rejecting a raw overlapping insert. Script and talking points:
[`docs/demo/demo-script.md`](docs/demo/demo-script.md).

---

## 3. Run the tests

Integration tests use Testcontainers, so **Docker must be running**.

| Command | Runs |
|---|---|
| `./mvnw test` | Unit tests only (fast, no Docker) |
| `./mvnw verify` | Unit + integration tests, Spotless + Checkstyle, coverage gate (≥ 90 %) |
| `./scripts/it.sh BookingConcurrencyIT` | One integration test class (pattern, comma-separated) |
| `./scripts/it.sh '*Concurrency*IT'` | All concurrency tests |

The concurrency suite fires 50 parallel bookings at one slot, also across two app instances
sharing one database, and checks after every race (with SQL) that no bay or technician is
double-booked.

---

## 4. API

Base path **`/api/v1`**, JSON. Full request and response schemas are in Swagger UI and in
[`docs/system-design/02-detailed-design.md` §14](docs/system-design/02-detailed-design.md#14-api-rest-apiv1-json).
**★** marks the core booking flow.

### Shared reads (any role)

| Method | Path | Purpose |
|---|---|---|
| GET | `/dealerships` | List dealerships |
| GET | `/dealerships/{dealershipId}/services` | Services offered there |
| ★ GET | `/dealerships/{dealershipId}/availability?serviceTypeIds=&from=&to=` | Free start times (≤ 14 days, local dates) |

### Customer (`X-User-Roles: CUSTOMER`)

| Method | Path | Purpose | Notes |
|---|---|---|---|
| GET | `/me/vehicles` | My vehicles | |
| POST | `/me/vehicles` | Add a vehicle | `409` if the VIN already exists |
| GET | `/me/vehicles/{vehicleId}` | One vehicle | |
| ★ POST | `/me/holds` | Hold a time for 10 minutes | `409 SLOT_UNAVAILABLE` + alternatives |
| GET | `/me/holds/{holdId}` | Read my hold (countdown) | |
| DELETE | `/me/holds/{holdId}` | Release my hold | idempotent `204` |
| ★ POST | `/me/appointments` | Book (with or without a hold) | **`Idempotency-Key` required**; `201` + `ETag` |
| GET | `/me/appointments` | My appointments | cursor paging: `limit`, `cursor` |
| GET | `/me/appointments/{appointmentId}` | One appointment | `ETag` |
| POST | `/me/appointments/{appointmentId}/reschedule` | Move to a newly held time | **`If-Match` required** |
| POST | `/me/appointments/{appointmentId}/cancel` | Cancel | `If-Match` optional |

### Dispatcher (`X-User-Roles: DISPATCHER`, dealership in `X-Dealership-Ids`)

All paths start with `/dealerships/{dealershipId}`.

| Method | Path | Purpose | Notes |
|---|---|---|---|
| ★ GET | `/schedule?date=&groupBy=BAY\|TECHNICIAN` | Gantt board for one day | read from the replica |
| GET | `/customers?q=` | Search by email, phone or registration prefix | ≥ 3 chars |
| POST | `/customers` | Create a customer (phone / walk-in) | |
| GET | `/customers/{customerId}` | Customer with vehicles | |
| POST | `/customers/{customerId}/vehicles` | Add a vehicle for them | |
| POST | `/appointments` | Book for a customer | **`Idempotency-Key` required**; may pin bay / technician |
| GET | `/appointments/{appointmentId}` | One appointment | `ETag` |
| ★ PATCH | `/appointments/{appointmentId}` | Move: time, bay, technician, duration (drag-and-drop) | **`If-Match` required**; `application/merge-patch+json` |
| POST | `/appointments/{appointmentId}/check-in` | `BOOKED → CHECKED_IN` | |
| POST | `/appointments/{appointmentId}/start` | `CHECKED_IN → IN_PROGRESS` | |
| POST | `/appointments/{appointmentId}/hold` | `IN_PROGRESS → ON_HOLD` (parts, approval) | |
| POST | `/appointments/{appointmentId}/resume` | `ON_HOLD → IN_PROGRESS` | |
| POST | `/appointments/{appointmentId}/complete` | `IN_PROGRESS → COMPLETED` | frees the rest of the slot |
| POST | `/appointments/{appointmentId}/no-show` | `BOOKED → NO_SHOW` | from 30 min after start |
| POST | `/appointments/{appointmentId}/cancel` | Cancel | |
| POST | `/resource-blocks` | Block a bay or technician (e.g. lift maintenance) | |
| DELETE | `/resource-blocks/{blockId}` | Remove a block | |

Status actions accept `If-Match` optionally. 32 endpoints in total.

### Conventions

| Topic | Rule |
|---|---|
| Times | Send ISO-8601 with any offset; responses are UTC (`Z`) plus the dealership `timeZone` |
| Safe retries | `Idempotency-Key` on booking `POST`s; a replay returns the original response + `Idempotency-Replayed: true` |
| Lost updates | Appointment responses carry `ETag: "<version>"`; send it back as `If-Match`. Missing ⇒ `428`, stale ⇒ `412` |
| Collections | `{ "items": [...] }`, paged ones add `nextCursor` |
| Errors | `application/problem+json` (RFC 9457) with a stable `code` and a `traceId` |

### Main error codes

| HTTP | `code` | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Malformed input; `errors[]` lists the fields |
| 401 | `UNAUTHENTICATED` | Identity headers missing |
| 403 / 404 | `FORBIDDEN` / `NOT_FOUND` | Not your dealership; other people's data is always `404` |
| 409 | `SLOT_UNAVAILABLE` | Time taken; body has `alternatives[]` |
| 409 | `SLOT_HELD`, `INVALID_TRANSITION` | Held by a customer; action not allowed in this status |
| 412 | `VERSION_CONFLICT` | Someone else changed the appointment; reload |
| 422 | `OUTSIDE_OPENING_HOURS`, `OUTSIDE_BOOKING_WINDOW`, `TECHNICIAN_NOT_QUALIFIED`, … | Breaks a business rule |
| 428 | `PRECONDITION_REQUIRED` | `If-Match` missing |
| 503 | `HOLDS_UNAVAILABLE` | Redis down; book directly instead |

---

## 5. How double booking is prevented

1. **Redis hold** (Lua, ~1 ms): turns most competing requests away before they reach the database.
2. **Advisory lock** per bay / technician, taken in sorted order, so writers queue instead of deadlocking.
3. **PostgreSQL `EXCLUDE` constraint**, the only real guarantee:

```sql
CONSTRAINT no_double_booking
  EXCLUDE USING gist (resource_id WITH =, period WITH &&) WHERE (active)
```

If Redis is down or wrong, booking still works: it is slower, and the database still decides.

---

## 6. Project layout

Packages follow features, not layers (no global `controller/`, `service/`, `dto/`), so each
feature's internals can stay package-private. `ArchitectureTest` (ArchUnit) enforces the
boundaries: the read side never uses write-side repositories, controllers never call
repositories, and `booking.domain` depends on no other booking package.

```
src/main/java/com/keyloop/scheduler/
  availability/   free start times (Redis slot ledger)
  hold/           10-minute slot holds (Redis + Lua)
  booking/        write side, split by use case:
    create/       new bookings: customer app and dispatcher walk-ins, idempotency
    change/       cancel, reschedule, dispatcher edits, check-in / start / hold / complete
    block/        block a bay or technician
    domain/       states, rules, booking policies, state machine, value types
    core/         repositories, resource locks, Redis ledger writes, bay + technician allocation
    event/        appointment and block events (written to the outbox)
  query/          my appointments, Gantt schedule (read side, replica)
  catalog/        dealerships and services
  customer/       customers and vehicles
  outbox/         events written in the booking transaction
  common/         identity headers, errors, ids, time, data sources
src/main/resources/
  db/migration/   V1 schema, V2 seed data (Flyway)
  redis/          Lua scripts
scripts/          demo.sh (local run + demo), it.sh (run selected integration tests)
docs/             system design, diagrams, UI mockups, demo, presentation, AI decision log
```

## 7. Documentation

| Doc | What |
|---|---|
| [High-level design](docs/system-design/01-high-level-design.md) | Architecture, key decisions, flows (start here) |
| [Detailed design](docs/system-design/02-detailed-design.md) | Data model, concurrency, CQRS, events, security, API |
| [Diagrams](docs/diagrams) and [UI mockups](docs/ui) | Architecture, ERD, sequences; customer app and dispatcher board |
| [AI decision log](docs/ai-collaboration/decision-log.md) | How AI was used: my direction, its challenge, the resolution |
| [Presentation](docs/presentation/README.md) and [demo script](docs/demo/demo-script.md) | Video deck and demo run-of-show |
