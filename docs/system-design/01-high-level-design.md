# Unified Service Scheduler — High-Level Design

> Keyloop Technical Assessment · Scenario A. A few pages: what we build, the decisions that
> shape it, and how each part flows. Every heading links to the full reference in
> [`02-detailed-design.md`](02-detailed-design.md); the debate behind each decision is in
> [`decision-log.md`](../ai-collaboration/decision-log.md).

## 1. Problem and scope

Dealership workshops still book service work by phone and paper. We replace that with one
system for two audiences:

- **Customers (B2C)** book service packages (Oil Change 45 min, Brake Inspection 75 min, …)
  online, 24/7.
- **Workshop dispatchers (B2B)** run the day on a live Gantt board of bays and technicians.

For every booking the system must reserve **one bay and one qualified technician for the
whole duration** and **never double-book either of them**, at global scale.

| Built (backend) | Designed only |
|---|---|
| REST API, PostgreSQL schema, Redis holds and cache, transactional outbox with a logging publisher, tests, Docker Compose stack with OpenSearch logs | UI (mockups), API gateway + JWT login, multi-region deployment, Kubernetes autoscaling, Kafka / SNS-SQS broker, Email and Reporting consumers |

## 2. Numbers that drive the design → [details §4](02-detailed-design.md#4-capacity-estimate)

Keyloop serves ~20,000 dealer sites in 100+ countries. Our design target is
**~10,000 reads/s and ~1,000 writes/s worldwide**.

- **Reads dominate** (customers browsing free times, dispatchers watching the board) ⇒ cache,
  read replicas, a separate read path.
- **Write conflicts only happen inside one dealership** (a few requests per second at most) ⇒
  a database constraint is enough to stop double booking; no distributed lock service.

## 3. Architecture at a glance → [details §5](02-detailed-design.md#5-architecture)

![Global architecture](../diagrams/01-global.png)

Each region (e.g. eu-west, us-east, ap-southeast) is a **fully independent stack**. The JWT
carries the user's `region` and the global load balancer routes on it. Regions share no data.

![Regional architecture](../diagrams/02-regional.png)

Inside a region: gateway → stateless Scheduler pods (autoscaled) → PostgreSQL primary for
writes, read replicas for reads, Redis for holds and cache. An outbox relay publishes events
to Kafka or SNS/SQS. Logs go to OpenSearch, metrics to Prometheus, traces to OpenTelemetry.

## 4. Key decisions

| # | Decision | Chosen | Why | Rejected |
|---|---|---|---|---|
| 1 | What implements the challenge | **Backend only**; UI as mockups | The brief asks for one layer done well; grading weighs tests and AI verification | Building both layers half-way |
| 2 | How to guarantee zero double booking | **PostgreSQL `EXCLUDE` constraint** on time ranges per bay/technician | The database makes overlap physically impossible, however many servers race | App-level check-then-insert (race), Redis/Redlock (no fencing ⇒ no guarantee), fixed slot grid (forces 15-min granularity) |
| 3 | Fast feedback under contention | **Redis slot holds** (10 min, like airline seats) via an atomic Lua script | Rejects colliding requests in ~1 ms without touching the database; good UX while the customer confirms | Using Redis as the source of truth — the DB stays the final judge |
| 4 | Read scaling | **Logical CQRS**: command side → primary, query side → replicas + Redis | Enough for the numbers; simple to run and test locally | Full CQRS with event-sourced projections (kept as the evolution path), event sourcing (incompatible with `EXCLUDE`) |
| 5 | Events to other services | **Transactional outbox** + `EventPublisher` port (Kafka or SNS/SQS adapter) | An event exists if and only if the change committed | Publishing directly from the request (lost or phantom events) |
| 6 | Identity | **Gateway validates JWT** and injects `X-User-*` headers; service trusts headers only from the gateway (private network / mTLS) | Keeps auth out of the service; one place to change identity provider | Service parsing JWTs itself |
| 7 | Multi-region | **One independent stack per region**, routed by the JWT `region` claim | Each dealership lives in exactly one database ⇒ no cross-region write conflicts, data residency by construction | Multi-master replication; cross-region booking deferred as a documented limitation |
| 8 | Time | **UTC everywhere**; only opening hours are local wall-clock + zone | One unambiguous instant for all maths; a workshop opens at 08:00 local all year | Storing local times (DST bugs) |
| 9 | Business rules | **Per-actor policy**: customers (2 h lead, 24 h change cutoff), dispatchers (walk-ins, overtime, no cutoff) | Real workshops need both; one shared allocation engine | One rule set for everyone (blocks walk-ins and overruns) |
| 10 | Appointment vs repair order | Scheduler owns the **booking lifecycle**; QC, hand-over and invoicing belong to the DMS repair order | Keeps the scheduler focused; matches how dealerships actually work | Modelling invoicing and collection in the scheduler |
| 11 | API style | REST, `/me` for customer data, `/dealerships/{d}` for staff, one action endpoint per state change, `ETag`/`If-Match`, `Idempotency-Key`, RFC 9457 errors | Predictable, validatable, safe to retry | One polymorphic "transitions" endpoint |

## 5. Flows

### 5.1 Customer books a service → [details §8](02-detailed-design.md#8-concurrency-control--zero-double-booking)

![Booking sequence](../diagrams/07-booking-sequence.png)

1. **Browse** — availability comes from Redis (no DB load): start times where a qualified
   technician *and* a bay are free for the full duration.
2. **Hold** — tapping a time runs an atomic Lua script that holds a bay + technician for
   10 minutes. If someone else got there first: `409` + nearest alternatives, no DB call.
3. **Confirm** — one database transaction inserts the appointment, both reservations and the
   outbox event. The `EXCLUDE` constraint is the final check; on conflict the customer gets
   `409` + alternatives and the Redis ledger heals itself.
4. **Notify** — the outbox relay publishes `AppointmentBooked`; Email and Reporting react.

Retries are safe (`Idempotency-Key`); if Redis is down, step 2 is skipped and booking still
works directly against the database.

### 5.2 Dispatcher runs the day → [details §6, §8.4](02-detailed-design.md#64-rules-per-actor-booking-reschedule-cancel)

- **Board** — `GET /schedule` from a read replica, refreshed every 30 s.
- **Drag-and-drop** — `PATCH` with `If-Match`: if someone else changed the job ⇒ `412`; if the
  new slot is taken ⇒ `409`; either way the card snaps back.
- **Walk-ins / phone bookings** — find or create the customer, book from "now", optionally
  pin a technician.
- **Blocks** — lift maintenance or technician leave are reservations too, so the same
  constraint protects them.

### 5.3 Appointment lifecycle → [details §6.2](02-detailed-design.md#62-appointment-lifecycle)

![Appointment lifecycle](../diagrams/04-lifecycle.png)

- **Hold** (waiting for parts or customer approval) frees the technician immediately; the bay
  is kept unless the car is moved to the yard.
- **Resume** re-reserves only the remaining minutes.
- **Complete** frees the rest of the slot; hand-over and invoicing continue in the DMS.
- **Cancel / no-show** release the time at once.

### 5.4 Reads (CQRS) → [details §9](02-detailed-design.md#9-cqrs-read-side-approach-a)

![CQRS paths](../diagrams/08-cqrs-paths.png)

Writes go to the primary and are always checked by the database. Reads go to Redis or
replicas and may be a fraction of a second stale — acceptable because writes never trust
them. "My appointments" reads the primary so a customer always sees what they just booked.

### 5.5 Events → [details §10](02-detailed-design.md#10-events--transactional-outbox)

![Event flow](../diagrams/09-event-flow.png)

At-least-once delivery, ordered per dealership; every event carries the appointment version
so consumers ignore stale ones. Payloads carry IDs, not personal data.

### 5.6 Security → [details §11](02-detailed-design.md#11-security)

- No anonymous endpoints; identity headers come only from the gateway.
- Customer data is always filtered by the caller; someone else's appointment is `404`, never
  `403`.
- Dispatchers can act only on dealerships listed in their token.
- The database repeats the key rules (composite foreign keys): no bay from another
  dealership, no vehicle from another customer.

### 5.7 Scaling and operations → [details §13, §18, §19](02-detailed-design.md#13-scaling-and-deployment)

![Scaling](../diagrams/05-scaling.png)

| Layer | How it scales |
|---|---|
| Scheduler pods | Stateless, Kubernetes HPA on CPU + requests/s, min 3 per region |
| Database | One primary per region (vertical) + 2–6 read replicas, PgBouncer pooling; shard by dealership if ever needed |
| Redis | Cluster mode; all keys of one dealership-day sit in one slot |
| Data growth | Reservations archived after 30 days, outbox purged after 7; appointments partition-ready by month |

**Quality gates:** unit tests ≥ 90 % line coverage on their own, plus integration tests against real PostgreSQL and Redis (Testcontainers) — merged coverage ≥ 90 %; Checkstyle, Spotless, ArchUnit, Snyk / OWASP scans.

Locally everything runs with one `docker compose up`: app, PostgreSQL primary + replica,
Redis, Fluent Bit, OpenSearch + Dashboards.

![Local stack](../diagrams/10-local-docker.png)

## 6. Data model → [details §7](02-detailed-design.md#7-data-model-postgresql-16)

![ERD](../diagrams/03-erd.png)

- `resource_reservation` is the heart: one row per bay or technician an appointment (or a
  block) occupies, protected by the `EXCLUDE` constraint.
- Composite foreign keys stop cross-dealership resources and foreign vehicles at the database
  level.
- Every index exists for a named query; times are UTC.

## 7. API overview → [details §14](02-detailed-design.md#14-api-rest-apiv1-json)

| Group | Endpoints | Examples |
|---|---|---|
| Shared reads (any role) | 3 | dealerships, services, availability |
| Customer (`/me`) | 11 | vehicles, holds, book, my appointments, reschedule, cancel |
| Dispatcher (`/dealerships/{d}`) | 17 | schedule, customers, book walk-in, drag-and-drop (`PATCH`), check-in / start / hold / resume / complete / no-show / cancel, blocks |

Status codes: `409` = clashes with current state, `412` = stale `If-Match`, `422` = breaks a
business rule, `400` = malformed. Each error has a fixed `title` and a human `detail` that
says what to do next.

## 8. UI (mockups) → [details §15](02-detailed-design.md#15-ui-design-mockups--not-built)

![Customer booking flow](../diagrams/ui-01-customer-booking.png)

![Dispatcher board](../diagrams/ui-03-dispatcher-board.png)

## 9. Top risks and how they are handled → [details §8.7](02-detailed-design.md#87-consistency-and-race-review)

| Risk | Handling |
|---|---|
| Two people book the same time | `EXCLUDE` constraint; Redis hold rejects most collisions earlier. **Proven by concurrency tests**: 50–100 simultaneous requests, mixed durations, every write path at once, and two app instances sharing one database ⇒ never more than one booking per bay/technician slot |
| Redis loses data or is down | Database still decides; ledger rebuilds itself; booking falls back to the direct path |
| Two dispatchers move the same job | `If-Match` ⇒ `412` |
| Client retries a booking | `Idempotency-Key` ⇒ same appointment returned |
| Event lost or sent twice | Outbox (never lost) + consumers deduplicate by event id and version |
| Hot slot flood | Rejected in Redis; PgBouncer protects connections |

## 10. Out of scope (documented, not built)

Maintaining dealership reference data (bays, technicians, skills, services — seeded by migrations for now; an admin API or DMS sync later), cross-region booking, multi-day jobs, courtesy cars and advisor capacity (the model leaves
room for them), pricing, technician clocking, QC / hand-over / invoicing (DMS repair order),
live push to the board (polling in the demo).

## 11. How GenAI was used → [details §21](02-detailed-design.md#21-how-genai-was-used-in-the-design-phase)

Claude Code with the Superpowers workflow: no code before an approved design; the agent was
asked to act as a sceptical principal engineer, grounded in the challenge PDF and real
products (Keyloop, Xtime), and every direction was challenged and resolved — 20 recorded
decisions in the [decision log](../ai-collaboration/decision-log.md).
