CREATE EXTENSION IF NOT EXISTS btree_gist;   -- lets GiST indexes mix "=" on uuid with "&&" on ranges

CREATE TABLE dealership (
  id                 uuid PRIMARY KEY,
  name               text NOT NULL,
  zone_id            text NOT NULL,            -- IANA zone; used only to interpret opening hours and for display
  bay_buffer_minutes int  NOT NULL DEFAULT 0 CHECK (bay_buffer_minutes BETWEEN 0 AND 60)
);

-- Local wall-clock hours on purpose (§7.3)
CREATE TABLE opening_hours (
  dealership_id uuid NOT NULL REFERENCES dealership(id),
  day_of_week   smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),  -- ISO, Monday = 1
  opens_at      time NOT NULL,
  closes_at     time NOT NULL CHECK (closes_at > opens_at),
  PRIMARY KEY (dealership_id, day_of_week)
);

CREATE TABLE skill        (code text PRIMARY KEY, name text NOT NULL);
CREATE TABLE service_type (id uuid PRIMARY KEY, code text UNIQUE NOT NULL, name text NOT NULL,
                           duration_minutes int NOT NULL CHECK (duration_minutes BETWEEN 5 AND 600),
                           required_skill text NOT NULL REFERENCES skill(code));
CREATE TABLE dealership_service (dealership_id uuid REFERENCES dealership(id),
                                 service_type_id uuid REFERENCES service_type(id),
                                 duration_override_minutes int CHECK (duration_override_minutes BETWEEN 5 AND 600),
                                 PRIMARY KEY (dealership_id, service_type_id));

-- UNIQUE (id, dealership_id) exists only so other tables can reference "this bay AND this dealership" (§7.1, fix 2)
CREATE TABLE service_bay (id uuid PRIMARY KEY, dealership_id uuid NOT NULL REFERENCES dealership(id),
                          name text NOT NULL, active boolean NOT NULL DEFAULT true,
                          UNIQUE (id, dealership_id));
CREATE TABLE technician  (id uuid PRIMARY KEY, dealership_id uuid NOT NULL REFERENCES dealership(id),
                          display_name text NOT NULL, active boolean NOT NULL DEFAULT true,
                          UNIQUE (id, dealership_id));
CREATE TABLE technician_skill (technician_id uuid REFERENCES technician(id),
                               skill_code text REFERENCES skill(code),
                               PRIMARY KEY (technician_id, skill_code));

CREATE TABLE customer (id uuid PRIMARY KEY,                 -- internal id (walk-ins have no login)
                       idp_subject uuid UNIQUE,              -- = X-User-Id when the customer has a login; NULL for walk-ins
                       display_name text NOT NULL, email text, phone text,
                       created_by uuid NOT NULL,             -- self or dispatcher
                       created_at timestamptz NOT NULL DEFAULT now());
-- Prefix search "abc%" needs text_pattern_ops; a plain btree can't serve LIKE under a non-C collation (§7.2, fix 6)
CREATE INDEX customer_email_prefix ON customer (lower(email) text_pattern_ops);
CREATE INDEX customer_phone_prefix ON customer (phone text_pattern_ops);

CREATE TABLE vehicle  (id uuid PRIMARY KEY, customer_id uuid NOT NULL REFERENCES customer(id),
                       vin char(17) NOT NULL, registration text, make text, model text,
                       model_year smallint, mileage int CHECK (mileage >= 0),
                       UNIQUE (customer_id, vin),            -- also serves "vehicles of customer X"
                       UNIQUE (id, customer_id));            -- target of the composite FK below (§7.1, fix 3)
CREATE INDEX vehicle_registration_prefix ON vehicle (upper(registration) text_pattern_ops);

CREATE TABLE appointment (
  id             uuid PRIMARY KEY,                 -- UUIDv7: time-ordered ⇒ inserts append to the btree's right edge
  dealership_id  uuid NOT NULL REFERENCES dealership(id),
  customer_id    uuid NOT NULL REFERENCES customer(id),
  vehicle_id     uuid NOT NULL,
  service_bay_id uuid NOT NULL,
  technician_id  uuid NOT NULL,
  start_at       timestamptz NOT NULL,             -- UTC; plain columns are easy to index, sort and partition (§7.4)
  end_at         timestamptz NOT NULL CHECK (end_at > start_at),
  period         tstzrange GENERATED ALWAYS AS (tstzrange(start_at, end_at, '[)')) STORED,
  status         text NOT NULL CHECK (status IN ('BOOKED','CHECKED_IN','IN_PROGRESS','ON_HOLD','COMPLETED','CANCELLED','NO_SHOW')),
  hold_reason    text CHECK (hold_reason IN ('PARTS','CUSTOMER_APPROVAL')),  -- set while ON_HOLD
  worked_minutes int NOT NULL DEFAULT 0,           -- accumulated on HOLD / COMPLETE
  started_at     timestamptz,                      -- start of the current IN_PROGRESS stint
  version        bigint NOT NULL DEFAULT 0,        -- optimistic locking
  customer_notes text,
  created_by     uuid NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  -- Composite FKs: the database itself refuses a bay/technician from another dealership
  -- and a vehicle owned by another customer (§7.1, fixes 2 and 3)
  FOREIGN KEY (service_bay_id, dealership_id) REFERENCES service_bay (id, dealership_id),
  FOREIGN KEY (technician_id,  dealership_id) REFERENCES technician  (id, dealership_id),
  FOREIGN KEY (vehicle_id,     customer_id)   REFERENCES vehicle     (id, customer_id)
);
CREATE INDEX appointment_dealership_day ON appointment (dealership_id, start_at)
  INCLUDE (end_at, status, service_bay_id, technician_id);           -- Gantt + dispatcher lists, index-only friendly
CREATE INDEX appointment_customer_timeline ON appointment (customer_id, start_at DESC, id);  -- "my appointments" + cursor

CREATE TABLE appointment_service (appointment_id uuid REFERENCES appointment(id),
                                  service_type_id uuid REFERENCES service_type(id),
                                  position smallint NOT NULL,
                                  duration_minutes int NOT NULL,   -- snapshot: later catalog changes don't rewrite history
                                  PRIMARY KEY (appointment_id, service_type_id));

-- The zero-double-booking table
CREATE TABLE resource_reservation (
  id             uuid PRIMARY KEY,
  dealership_id  uuid NOT NULL,
  -- Exactly one of the two is set: real FKs instead of an unchecked polymorphic id (§7.1, fix 1)
  service_bay_id uuid,
  technician_id  uuid,
  resource_id    uuid GENERATED ALWAYS AS (coalesce(service_bay_id, technician_id)) STORED,
  appointment_id uuid REFERENCES appointment(id),  -- NULL for blocks
  kind           text NOT NULL CHECK (kind IN ('APPOINTMENT','BLOCK')),
  start_at       timestamptz NOT NULL,
  end_at         timestamptz NOT NULL CHECK (end_at > start_at),
  period         tstzrange GENERATED ALWAYS AS (tstzrange(start_at, end_at, '[)')) STORED,
  active         boolean NOT NULL DEFAULT true,
  reason         text,                             -- for blocks
  CHECK (num_nonnulls(service_bay_id, technician_id) = 1),
  CHECK ((kind = 'APPOINTMENT') = (appointment_id IS NOT NULL)),
  FOREIGN KEY (service_bay_id, dealership_id) REFERENCES service_bay (id, dealership_id),
  FOREIGN KEY (technician_id,  dealership_id) REFERENCES technician  (id, dealership_id),
  CONSTRAINT no_double_booking
    EXCLUDE USING gist (resource_id WITH =, period WITH &&) WHERE (active)
);
-- GiST, not btree: "period && :window" can only use a GiST index (§7.2, fix 4)
CREATE INDEX reservation_dealership_window ON resource_reservation
  USING gist (dealership_id, period) WHERE active;
-- Every reschedule / transition updates reservations BY appointment_id (§7.2, fix 5)
CREATE INDEX reservation_by_appointment ON resource_reservation (appointment_id) WHERE appointment_id IS NOT NULL;
CREATE INDEX reservation_archival ON resource_reservation (end_at);   -- nightly archival scan

CREATE TABLE outbox_event (
  id               uuid PRIMARY KEY,
  aggregate_type   text NOT NULL,
  aggregate_id     uuid NOT NULL,
  aggregate_version bigint NOT NULL,
  dealership_id    uuid NOT NULL,                  -- partition / ordering key for the broker
  event_type       text NOT NULL,
  payload          jsonb NOT NULL,
  occurred_at      timestamptz NOT NULL DEFAULT now(),
  published_at     timestamptz
);
CREATE INDEX outbox_unpublished ON outbox_event (occurred_at) WHERE published_at IS NULL;  -- relay poll stays tiny
CREATE INDEX outbox_published   ON outbox_event (published_at) WHERE published_at IS NOT NULL; -- cleanup job

CREATE TABLE idempotency_key (
  key           text NOT NULL,
  user_id       uuid NOT NULL,
  request_hash  text NOT NULL,
  response_code int,
  response_body jsonb,
  created_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, key)
);
CREATE INDEX idempotency_expiry ON idempotency_key (created_at);
