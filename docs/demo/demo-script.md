# Video Script — Unified Service Scheduler

Target length **~7 minutes** (the brief allows 5–10): slides ≈ 4:50, live demo ≈ 2:15.
Slides: the "Unified Service Scheduler — 5-min talk" deck; each slide's speaker notes hold its
exact lines. This file covers the run-of-show and the live demo.

## Before recording (off camera)

```bash
./scripts/demo.sh up                       # terminal 1: fresh PostgreSQL + Redis, then the app
./scripts/it.sh BookingConcurrencyIT       # terminal 2, ~75 s: run it now, show the result later
```

Ready when `curl -s localhost:8080/actuator/health` says `UP`.

- Terminal font ≥ 18 pt, dark theme, window ~120 columns. Clear the screen.
- Rehearse once, then **reset**: Ctrl+C the app and run `./scripts/demo.sh up` again (the demo
  books a real appointment; a second run would find 09:00 taken).
- Close Slack / notifications.

## Two ways to drive the demo

- **Postman** (looks friendlier on video): import
  `docs/demo/scheduler-demo.postman_collection.json`, send requests 1 → 7 in order. Each one has a
  test that turns green; ids, ETags and the Idempotency-Key are passed along automatically. Then
  step 8 in a terminal: `./scripts/demo.sh sql 11:00`.
- **Terminal only**: `./scripts/demo.sh`, Enter between steps (same 8 steps).

Either way the lines below are the same.

## Run-of-show

| Time | On screen | What happens |
|---|---|---|
| 0:00 | Slides 1–11 | Problem, use cases, design, scaling, scope, API flow, data model, indexes, Redis, double booking (speaker notes) |
| 3:45 | Slide 12 "Live demo" | One sentence: "Let me show it running." Switch to terminal |
| 3:50 | Postman requests 1–7, then `./scripts/demo.sh sql 11:00` | 8 steps below |
| 5:50 | Terminal 2 | Concurrency test result (30 / 30 green) |
| 6:05 | Slides 13–16 | Proof (short, the demo already showed it), AI collaboration, takeaways |
| 7:05 | End | |

## Live demo — what to say at each step

Press **Enter** only after you finish the sentence; let each result sit for a second.

| Step | Screen shows | Say |
|---|---|---|
| 1 | Free start times for an oil change | "The Solo dealership has one bay and one technician, so any overlap is a real conflict. A customer sees free 45-minute start times, served from Redis and replicas." |
| 2 | `201` hold, `expiresAt` in 10 min | "Alice taps 09:00. One atomic Lua script holds the bay *and* the technician for ten minutes, like an airline seat." |
| 3 | `409 SLOT_UNAVAILABLE` + alternatives | "Bob tries 09:15, which overlaps. Rejected in about a millisecond, without touching the database, and he gets the nearest free times instead of a dead end." |
| 4 | `201`, `ETag: "0"`, status `BOOKED` | "Alice confirms. One transaction writes the appointment, both reservations and the outbox event. The EXCLUDE constraint is the final check." |
| 5 | Same `Location`, `Idempotency-Replayed: true` | "Her phone loses signal and retries. Same Idempotency-Key, so she gets the same appointment back, not a second booking." |
| 6 | `200`, `ETag: "1"` | "In the workshop, a dispatcher drags the card to 11:00. The PATCH carries If-Match, the version they were looking at." |
| 7 | `412 VERSION_CONFLICT` | "A second dispatcher on a stale board drags the same card. 412: someone changed it; the UI snaps the card back and reloads." |
| 8 | `ERROR: conflicting key value violates exclusion constraint "no_double_booking"` | "Finally, forget the app: I insert an overlapping row straight into PostgreSQL. The database itself refuses. That's why I trust it as the judge, and not Redis or application code." |
| Test | `tests="30" errors="0" skipped="0" failures="0"` (terminal 2) | "And this is the concurrency suite: fifty simultaneous bookings of one slot, repeated, plus every write path at once. Never more than one booking per bay or technician." |

To show the test result cleanly in terminal 2:

```bash
grep -ho 'tests="[0-9]*" errors="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' \
  target/failsafe-reports/TEST-*BookingConcurrencyIT.xml
```

## If something goes wrong on camera

| Symptom | Fix |
|---|---|
| Step 2 returns `409` | Data from a previous run: Ctrl+C the app, `./scripts/demo.sh up` |
| `Connection refused` | App not started yet: wait for "Started SchedulerApplication" |
| Step 1 shows no times | `DAY` is two days ahead; Solo is open every day 08:00–18:00 London, so this only happens if the DB is not seeded: restart the app |

After recording: `./scripts/demo.sh down`.
