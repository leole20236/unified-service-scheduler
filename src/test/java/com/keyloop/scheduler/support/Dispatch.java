package com.keyloop.scheduler.support;

import static io.restassured.RestAssured.given;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Helpers for dispatcher integration tests (board, drag-and-drop, actions, blocks). */
public final class Dispatch {

  public static final UUID DISPATCHER = UUID.fromString("0192f0b9-0000-7000-8000-00000000d15a");
  public static final String APPOINTMENTS = "/api/v1/dealerships/{d}/appointments";
  public static final String APPOINTMENT = APPOINTMENTS + "/{id}";
  public static final String BLOCKS = "/api/v1/dealerships/{d}/resource-blocks";
  public static final String SCHEDULE = "/api/v1/dealerships/{d}/schedule";
  public static final String MERGE_PATCH = "application/merge-patch+json";

  private Dispatch() {}

  /** A request as a dispatcher of the dealership. */
  public static RequestSpecification as(UUID dealership) {
    return given().headers(Headers.dispatcher(DISPATCHER, dealership));
  }

  /** {@code POST /dealerships/{d}/appointments}, optionally pinned. */
  public static Response book(
      UUID dealership,
      UUID customer,
      UUID vehicle,
      Instant start,
      UUID bay,
      UUID technician,
      boolean overrideHold,
      UUID... services) {
    Map<String, Object> body = new HashMap<>();
    body.put("customerId", customer.toString());
    body.put("vehicleId", vehicle.toString());
    body.put("serviceTypeIds", Arrays.stream(services).map(UUID::toString).toList());
    body.put("start", start.toString());
    if (bay != null) {
      body.put("serviceBayId", bay.toString());
    }
    if (technician != null) {
      body.put("technicianId", technician.toString());
    }
    body.put("overrideHold", overrideHold);
    return as(dealership)
        .header("Idempotency-Key", "d-" + UUID.randomUUID())
        .contentType(ContentType.JSON)
        .body(body)
        .post(APPOINTMENTS, dealership);
  }

  /** A dispatcher booking that must succeed; returns the appointment id. */
  public static String booked(
      UUID dealership, UUID customer, UUID vehicle, Instant start, UUID... services) {
    Response r = book(dealership, customer, vehicle, start, null, null, false, services);
    if (r.statusCode() != 201) {
      throw new AssertionError("Booking failed: " + r.asString());
    }
    return r.jsonPath().getString("id");
  }

  /** {@code GET /dealerships/{d}/appointments/{id}}. */
  public static Response get(UUID dealership, String id) {
    return as(dealership).get(APPOINTMENT, dealership, id);
  }

  /** {@code PATCH} with merge-patch and the given If-Match (null: none). */
  public static Response patch(
      UUID dealership, String id, String ifMatch, Map<String, Object> body) {
    RequestSpecification r = as(dealership).contentType(MERGE_PATCH).body(body);
    if (ifMatch != null) {
      r = r.header("If-Match", ifMatch);
    }
    return r.patch(APPOINTMENT, dealership, id);
  }

  /** {@code POST …/{id}/{action}} with an optional If-Match and JSON body. */
  public static Response action(
      UUID dealership, String id, String action, String ifMatch, Object body) {
    RequestSpecification r = as(dealership);
    if (ifMatch != null) {
      r = r.header("If-Match", ifMatch);
    }
    // JSON even without a body: RestAssured would otherwise send a form content type.
    r = r.contentType(ContentType.JSON);
    if (body != null) {
      r = r.body(body);
    }
    return r.post(APPOINTMENT + "/" + action, dealership, id);
  }

  /** An action that must succeed. */
  public static Response acted(UUID dealership, String id, String action, Object body) {
    Response r = action(dealership, id, action, null, body);
    if (r.statusCode() != 200) {
      throw new AssertionError(action + " failed: " + r.asString());
    }
    return r;
  }

  /** {@code GET /dealerships/{d}/schedule}. */
  public static Response schedule(UUID dealership, LocalDate date, String groupBy) {
    RequestSpecification r = as(dealership).queryParam("date", date.toString());
    if (groupBy != null) {
      r = r.queryParam("groupBy", groupBy);
    }
    return r.get(SCHEDULE, dealership);
  }

  /** {@code POST /dealerships/{d}/resource-blocks}. */
  public static Response block(
      UUID dealership,
      String type,
      UUID resource,
      Instant start,
      Instant end,
      String reason,
      boolean overrideHold) {
    Map<String, Object> body = new HashMap<>();
    body.put("resourceType", type);
    body.put("resourceId", resource.toString());
    body.put("start", start.toString());
    body.put("end", end.toString());
    body.put("reason", reason);
    body.put("overrideHold", overrideHold);
    return as(dealership).contentType(ContentType.JSON).body(body).post(BLOCKS, dealership);
  }

  /** {@code DELETE /dealerships/{d}/resource-blocks/{id}}. */
  public static Response unblock(UUID dealership, String id) {
    return as(dealership).delete(BLOCKS + "/{id}", dealership, id);
  }

  /** The database's clock. */
  public static Instant dbNow(JdbcTemplate jdbc) {
    return jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
  }

  /**
   * Test setup only: moves an appointment and its active reservations so that it starts at {@code
   * start}, keeping every length (the EXCLUDE constraint still applies).
   */
  public static void shift(JdbcTemplate jdbc, String id, Instant start) {
    jdbc.update(
        """
        UPDATE resource_reservation r
        SET start_at = r.start_at + (?::timestamptz - a.start_at),
            end_at = r.end_at + (?::timestamptz - a.start_at)
        FROM appointment a
        WHERE a.id = r.appointment_id AND r.active AND a.id = ?::uuid
        """,
        Timestamp.from(start),
        Timestamp.from(start),
        id);
    jdbc.update(
        "UPDATE appointment SET end_at = end_at + (?::timestamptz - start_at), start_at = ?"
            + " WHERE id = ?::uuid",
        Timestamp.from(start),
        Timestamp.from(start),
        id);
  }

  /**
   * Test setup only: opens the dealership 00:00-23:59 every day, so jobs can start "now" whatever
   * the time of the test run. Undo with {@link #restoreSeedHours}.
   */
  public static void alwaysOpen(JdbcTemplate jdbc, UUID dealership) {
    jdbc.update("DELETE FROM opening_hours WHERE dealership_id = ?", dealership);
    jdbc.update(
        "INSERT INTO opening_hours (dealership_id, day_of_week, opens_at, closes_at)"
            + " SELECT ?, d, '00:00', '23:59' FROM generate_series(1, 7) d",
        dealership);
  }

  /** Puts back the seed opening hours of Solo and Riverside (V2__seed.sql). */
  public static void restoreSeedHours(JdbcTemplate jdbc) {
    jdbc.update(
        "DELETE FROM opening_hours WHERE dealership_id IN (?, ?)",
        Fixtures.SOLO,
        Fixtures.RIVERSIDE);
    jdbc.update(
        "INSERT INTO opening_hours (dealership_id, day_of_week, opens_at, closes_at)"
            + " SELECT ?, d, '08:00', '18:00' FROM generate_series(1, 7) d",
        Fixtures.SOLO);
    jdbc.update(
        "INSERT INTO opening_hours (dealership_id, day_of_week, opens_at, closes_at)"
            + " SELECT ?, d, '08:00', '18:00' FROM generate_series(1, 6) d",
        Fixtures.RIVERSIDE);
  }
}
