package com.keyloop.scheduler.booking.change;

import static com.keyloop.scheduler.support.Bookings.LONDON;
import static com.keyloop.scheduler.support.Bookings.at;
import static com.keyloop.scheduler.support.Bookings.body;
import static com.keyloop.scheduler.support.Bookings.book;
import static com.keyloop.scheduler.support.Bookings.next;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Headers;
import com.keyloop.scheduler.support.Holds;
import com.keyloop.scheduler.support.IntegrationTest;
import com.keyloop.scheduler.support.NoDoubleBookingAssert;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reschedule (via a hold) and cancel of a customer's own appointment, end to end. */
class CustomerChangeApiIT extends IntegrationTest {

  private static final String ME = "/api/v1/me/appointments";
  private static final LocalDate WEDNESDAY = next(DayOfWeek.WEDNESDAY);
  private static final Instant WED_NINE = at(WEDNESDAY, 9, 0, LONDON);
  private static final Instant THU_ELEVEN = at(WEDNESDAY.plusDays(1), 11, 0, LONDON);

  @BeforeEach
  void configure() {
    RestAssured.port = port;
  }

  private static String aliceBooksSolo(Instant start) {
    Response r =
        book(
            Fixtures.ALICE_SUB,
            body(Fixtures.SOLO, Fixtures.ALICE_FOCUS, start, Fixtures.OIL_CHANGE),
            "k-" + UUID.randomUUID());
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(201);
    return r.jsonPath().getString("id");
  }

  private static String aliceHolds(String appointmentId, Instant start) {
    Response r =
        Holds.holdToReschedule(
            Fixtures.ALICE_SUB,
            appointmentId,
            Fixtures.SOLO,
            Fixtures.ALICE_FOCUS,
            start,
            Fixtures.OIL_CHANGE);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(201);
    return r.jsonPath().getString("id");
  }

  private static Response reschedule(UUID subject, String id, String ifMatch, String holdId) {
    var request = given().headers(Headers.customer(subject)).contentType(ContentType.JSON);
    if (ifMatch != null) {
      request = request.header("If-Match", ifMatch);
    }
    return request.body(Map.of("holdId", holdId)).post(ME + "/" + id + "/reschedule");
  }

  private static Response cancel(UUID subject, String id, String ifMatch) {
    var request = given().headers(Headers.customer(subject));
    if (ifMatch != null) {
      request = request.header("If-Match", ifMatch);
    }
    return request.post(ME + "/" + id + "/cancel");
  }

  /** Moves the appointment to start in five hours, keeping its rows consistent. */
  private void startsInFiveHours(String id) {
    jdbc.update(
        "UPDATE appointment SET start_at = now() + interval '5 hours',"
            + " end_at = now() + interval '5 hours 45 minutes' WHERE id = ?::uuid",
        id);
    jdbc.update(
        "UPDATE resource_reservation SET start_at = now() + interval '5 hours',"
            + " end_at = now() + interval '5 hours 45 minutes' WHERE appointment_id = ?::uuid",
        id);
  }

  @Test
  void rescheduleMovesBothReservations() {
    String id = aliceBooksSolo(WED_NINE);
    String holdId = aliceHolds(id, THU_ELEVEN);
    // Loads Wednesday's ledger: Bob is refused 09:00 while Alice holds it.
    Holds.hold(Fixtures.BOB_SUB, Fixtures.SOLO, Fixtures.BOB_GOLF, WED_NINE, Fixtures.OIL_CHANGE)
        .then()
        .statusCode(409);

    Response moved = reschedule(Fixtures.ALICE_SUB, id, "\"0\"", holdId);

    moved
        .then()
        .statusCode(200)
        .header("ETag", "\"1\"")
        .body("version", equalTo(1))
        .body("start", equalTo(THU_ELEVEN.toString()))
        .body("end", equalTo(THU_ELEVEN.plusSeconds(45 * 60).toString()));
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT resource_id, start_at, end_at, active FROM resource_reservation"
                + " WHERE appointment_id = ?::uuid",
            id);
    assertThat(rows).hasSize(2);
    assertThat(rows)
        .allSatisfy(
            r -> {
              assertThat(r.get("active")).isEqualTo(true);
              assertThat(((Timestamp) r.get("start_at")).toInstant()).isEqualTo(THU_ELEVEN);
              assertThat(((Timestamp) r.get("end_at")).toInstant())
                  .isEqualTo(THU_ELEVEN.plusSeconds(45 * 60));
            });
    Map<String, Object> outbox =
        jdbc.queryForMap(
            "SELECT aggregate_version, payload::text AS payload FROM outbox_event"
                + " WHERE event_type = 'AppointmentRescheduled'");
    assertThat(outbox.get("aggregate_version")).isEqualTo(1L);
    assertThat((String) outbox.get("payload"))
        .contains(WED_NINE.toString(), THU_ELEVEN.toString())
        .contains("\"aggregateVersion\": 1");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM appointment WHERE id = ?::uuid", Long.class, id))
        .isEqualTo(1L);
    // Old slot free in availability and in the Redis ledger (Bob may hold it now).
    assertThat(Holds.starts(Fixtures.BOB_SUB, Fixtures.SOLO, WEDNESDAY, Fixtures.OIL_CHANGE))
        .contains(WED_NINE.toString());
    Holds.hold(Fixtures.BOB_SUB, Fixtures.SOLO, Fixtures.BOB_GOLF, WED_NINE, Fixtures.OIL_CHANGE)
        .then()
        .statusCode(201);
    // The new slot is taken for everyone else, and the consumed hold is gone.
    Holds.hold(Fixtures.BOB_SUB, Fixtures.SOLO, Fixtures.BOB_GOLF, THU_ELEVEN, Fixtures.OIL_CHANGE)
        .then()
        .statusCode(409);
    Holds.get(Fixtures.ALICE_SUB, holdId).then().statusCode(404);
    NoDoubleBookingAssert.verify(JdbcClient.create(jdbc));
  }

  @Test
  void rescheduleStaleVersion412() {
    String id = aliceBooksSolo(WED_NINE);
    String holdId = aliceHolds(id, THU_ELEVEN);

    reschedule(Fixtures.ALICE_SUB, id, "\"5\"", holdId)
        .then()
        .statusCode(412)
        .body("code", equalTo("VERSION_CONFLICT"));
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM appointment WHERE id = ?::uuid", Long.class, id))
        .isZero();
  }

  @Test
  void rescheduleWithoutIfMatch428() {
    String id = aliceBooksSolo(WED_NINE);
    String holdId = aliceHolds(id, THU_ELEVEN);

    reschedule(Fixtures.ALICE_SUB, id, null, holdId)
        .then()
        .statusCode(428)
        .body("code", equalTo("PRECONDITION_REQUIRED"));
    reschedule(Fixtures.ALICE_SUB, id, "3", holdId).then().statusCode(400);
  }

  @Test
  void rescheduleWithin24hIs422() {
    String id = aliceBooksSolo(WED_NINE);
    startsInFiveHours(id);
    String holdId = aliceHolds(id, THU_ELEVEN);

    reschedule(Fixtures.ALICE_SUB, id, "\"0\"", holdId)
        .then()
        .statusCode(422)
        .body("code", equalTo("CHANGE_WINDOW_CLOSED"))
        .body("detail", containsString("starts in"));
  }

  @Test
  void rescheduleOthersIs404AndForeignHoldIs404() {
    String id = aliceBooksSolo(WED_NINE);
    String holdId = aliceHolds(id, THU_ELEVEN);

    reschedule(Fixtures.BOB_SUB, id, "\"0\"", holdId).then().statusCode(404);
    String bobHold =
        Holds.hold(
                Fixtures.BOB_SUB,
                Fixtures.SOLO,
                Fixtures.BOB_GOLF,
                at(WEDNESDAY.plusDays(2), 11, 0, LONDON),
                Fixtures.OIL_CHANGE)
            .jsonPath()
            .getString("id");
    reschedule(Fixtures.ALICE_SUB, id, "\"0\"", bobHold).then().statusCode(404);
  }

  @Test
  void rescheduleWithHoldForOtherServicesIs422() {
    String id = aliceBooksSolo(WED_NINE);
    String holdId =
        Holds.holdToReschedule(
                Fixtures.ALICE_SUB,
                id,
                Fixtures.SOLO,
                Fixtures.ALICE_FOCUS,
                THU_ELEVEN,
                Fixtures.BRAKE_INSPECTION)
            .jsonPath()
            .getString("id");

    reschedule(Fixtures.ALICE_SUB, id, "\"0\"", holdId)
        .then()
        .statusCode(422)
        .body("code", equalTo("HOLD_MISMATCH"));
  }

  @Test
  void rescheduleOntoSlotTakenMeanwhileIs409WithAlternatives() {
    String id = aliceBooksSolo(WED_NINE);
    String holdId = aliceHolds(id, THU_ELEVEN);
    // A direct booking ignores holds; the database decides.
    book(
            Fixtures.BOB_SUB,
            body(Fixtures.SOLO, Fixtures.BOB_GOLF, THU_ELEVEN, Fixtures.OIL_CHANGE),
            "bob")
        .then()
        .statusCode(201);

    reschedule(Fixtures.ALICE_SUB, id, "\"0\"", holdId)
        .then()
        .statusCode(409)
        .body("code", equalTo("SLOT_UNAVAILABLE"))
        .body("alternatives.size()", org.hamcrest.Matchers.greaterThan(0));
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM appointment WHERE id = ?::uuid", Long.class, id))
        .isZero();
    NoDoubleBookingAssert.verify(JdbcClient.create(jdbc));
  }

  @Test
  void cancelFreesSlot() {
    String id = aliceBooksSolo(WED_NINE);

    cancel(Fixtures.ALICE_SUB, id, null)
        .then()
        .statusCode(200)
        .header("ETag", "\"1\"")
        .body("status", equalTo("CANCELLED"))
        .body("version", equalTo(1));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM resource_reservation WHERE appointment_id = ?::uuid"
                    + " AND active",
                Integer.class,
                id))
        .isZero();
    Map<String, Object> outbox =
        jdbc.queryForMap(
            "SELECT aggregate_version, payload::text AS payload FROM outbox_event"
                + " WHERE event_type = 'AppointmentCancelled'");
    assertThat(outbox.get("aggregate_version")).isEqualTo(1L);
    book(
            Fixtures.BOB_SUB,
            body(Fixtures.SOLO, Fixtures.BOB_GOLF, WED_NINE, Fixtures.OIL_CHANGE),
            "bob")
        .then()
        .statusCode(201);
    NoDoubleBookingAssert.verify(JdbcClient.create(jdbc));
  }

  @Test
  void cancelRemovesTheSlotFromTheLedger() {
    String id = aliceBooksSolo(WED_NINE);
    Holds.hold(Fixtures.BOB_SUB, Fixtures.SOLO, Fixtures.BOB_GOLF, WED_NINE, Fixtures.OIL_CHANGE)
        .then()
        .statusCode(409);

    cancel(Fixtures.ALICE_SUB, id, "\"0\"").then().statusCode(200);

    Holds.hold(Fixtures.BOB_SUB, Fixtures.SOLO, Fixtures.BOB_GOLF, WED_NINE, Fixtures.OIL_CHANGE)
        .then()
        .statusCode(201);
  }

  @Test
  void cancelTwiceIs409InvalidTransition() {
    String id = aliceBooksSolo(WED_NINE);
    cancel(Fixtures.ALICE_SUB, id, null).then().statusCode(200);

    cancel(Fixtures.ALICE_SUB, id, null)
        .then()
        .statusCode(409)
        .body("code", equalTo("INVALID_TRANSITION"));
  }

  @Test
  void cancelOthersIs404() {
    String id = aliceBooksSolo(WED_NINE);

    cancel(Fixtures.BOB_SUB, id, null).then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    cancel(Fixtures.ALICE_SUB, UUID.randomUUID().toString(), null).then().statusCode(404);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM appointment WHERE id = ?::uuid", String.class, id))
        .isEqualTo("BOOKED");
  }

  @Test
  void cancelWithin24hIs422AndStaleIfMatchIs412() {
    String id = aliceBooksSolo(WED_NINE);
    cancel(Fixtures.ALICE_SUB, id, "\"9\"")
        .then()
        .statusCode(412)
        .body("code", equalTo("VERSION_CONFLICT"));
    startsInFiveHours(id);

    cancel(Fixtures.ALICE_SUB, id, null)
        .then()
        .statusCode(422)
        .body("code", equalTo("CHANGE_WINDOW_CLOSED"))
        .body("detail", containsString("starts in 4 hours"));
  }

  @Test
  void rescheduleToOverlapOwnSlotWorks() {
    String id = aliceBooksSolo(WED_NINE);
    Instant quarterPast = WED_NINE.plusSeconds(15 * 60);
    // Without rescheduleOf the ledger refuses the overlap with the own slot.
    Holds.hold(
            Fixtures.ALICE_SUB,
            Fixtures.SOLO,
            Fixtures.ALICE_FOCUS,
            quarterPast,
            Fixtures.OIL_CHANGE)
        .then()
        .statusCode(409);
    String holdId = aliceHolds(id, quarterPast);

    reschedule(Fixtures.ALICE_SUB, id, "\"0\"", holdId)
        .then()
        .statusCode(200)
        .header("ETag", "\"1\"")
        .body("version", equalTo(1))
        .body("start", equalTo(quarterPast.toString()));
    assertThat(
            jdbc.queryForList(
                "SELECT start_at FROM resource_reservation WHERE appointment_id = ?::uuid"
                    + " AND active",
                Timestamp.class,
                id))
        .hasSize(2)
        .allSatisfy(t -> assertThat(t.toInstant()).isEqualTo(quarterPast));
    NoDoubleBookingAssert.verify(JdbcClient.create(jdbc));
  }

  @Test
  void rescheduleOfSomeoneElsesAppointmentIs404() {
    String id = aliceBooksSolo(WED_NINE);

    Holds.holdToReschedule(
            Fixtures.BOB_SUB, id, Fixtures.SOLO, Fixtures.BOB_GOLF, THU_ELEVEN, Fixtures.OIL_CHANGE)
        .then()
        .statusCode(404);
  }

  @Test
  void rescheduleWithPlainHoldIs422() {
    String id = aliceBooksSolo(WED_NINE);
    String plain =
        Holds.hold(
                Fixtures.ALICE_SUB,
                Fixtures.SOLO,
                Fixtures.ALICE_FOCUS,
                THU_ELEVEN,
                Fixtures.OIL_CHANGE)
            .jsonPath()
            .getString("id");

    reschedule(Fixtures.ALICE_SUB, id, "\"0\"", plain)
        .then()
        .statusCode(422)
        .body("code", equalTo("HOLD_MISMATCH"));
  }

  @Test
  void alternativesAfterConflictDoNotCountTheOwnSlot() {
    Instant noon = at(WEDNESDAY, 12, 0, LONDON);
    Instant quarterPastEleven = at(WEDNESDAY, 11, 15, LONDON);
    Instant bobStart = at(WEDNESDAY, 10, 45, LONDON);
    String id = aliceBooksSolo(noon);
    String holdId = aliceHolds(id, quarterPastEleven);
    book(
            Fixtures.BOB_SUB,
            body(Fixtures.SOLO, Fixtures.BOB_GOLF, bobStart, Fixtures.OIL_CHANGE),
            "bob")
        .then()
        .statusCode(201);

    Response refused = reschedule(Fixtures.ALICE_SUB, id, "\"0\"", holdId);

    refused.then().statusCode(409).body("code", equalTo("SLOT_UNAVAILABLE"));
    // 11:30 overlaps only Alice's own 12:00 slot, so it is offered.
    assertThat(refused.jsonPath().getList("alternatives", String.class))
        .contains(at(WEDNESDAY, 11, 30, LONDON).toString());
  }
}
