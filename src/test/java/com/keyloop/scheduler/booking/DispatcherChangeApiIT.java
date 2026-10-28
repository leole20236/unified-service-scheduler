package com.keyloop.scheduler.booking;

import static com.keyloop.scheduler.support.Bookings.LONDON;
import static com.keyloop.scheduler.support.Bookings.at;
import static com.keyloop.scheduler.support.Bookings.next;
import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.support.ConcurrencyHarness;
import com.keyloop.scheduler.support.Dispatch;
import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Holds;
import com.keyloop.scheduler.support.IntegrationTest;
import com.keyloop.scheduler.support.NoDoubleBookingAssert;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Dispatcher drag-and-drop and resize ({@code PATCH}) end to end. */
class DispatcherChangeApiIT extends IntegrationTest {

  private static final LocalDate WEDNESDAY = next(DayOfWeek.WEDNESDAY);
  private static final Instant WED_NINE = at(WEDNESDAY, 9, 0, LONDON);
  private static final Instant WED_ELEVEN = at(WEDNESDAY, 11, 0, LONDON);
  private static final Instant WED_ONE = at(WEDNESDAY, 13, 0, LONDON);

  private JdbcClient client;

  @BeforeEach
  void configure() {
    RestAssured.port = port;
    client = JdbcClient.create(jdbc);
  }

  private static String soloJob(Instant start, UUID customer, UUID vehicle) {
    return Dispatch.booked(Fixtures.SOLO, customer, vehicle, start, Fixtures.OIL_CHANGE);
  }

  private Map<String, Object> reservation(String id, String column) {
    return jdbc.queryForMap(
        "SELECT "
            + column
            + "::text AS resource, start_at, end_at FROM resource_reservation"
            + " WHERE appointment_id = ?::uuid AND active AND "
            + column
            + " IS NOT NULL",
        id);
  }

  private static Instant instant(Object timestamp) {
    return ((Timestamp) timestamp).toInstant();
  }

  @Test
  void dragToNewTimeAndBay() {
    String id =
        Dispatch.booked(
            Fixtures.RIVERSIDE,
            Fixtures.ALICE,
            Fixtures.ALICE_FOCUS,
            WED_NINE,
            Fixtures.OIL_CHANGE);
    String oldBay =
        jdbc.queryForObject(
            "SELECT service_bay_id::text FROM appointment WHERE id = ?::uuid", String.class, id);
    String newBay =
        Fixtures.RIVERSIDE_BAY_1.toString().equals(oldBay)
            ? Fixtures.RIVERSIDE_BAY_2.toString()
            : Fixtures.RIVERSIDE_BAY_1.toString();

    Response r =
        Dispatch.patch(
            Fixtures.RIVERSIDE,
            id,
            "\"0\"",
            Map.of("start", WED_ONE.toString(), "serviceBayId", newBay));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.header("ETag")).isEqualTo("\"1\"");
    assertThat(r.jsonPath().getInt("version")).isEqualTo(1);
    assertThat(r.jsonPath().getString("start")).isEqualTo(WED_ONE.toString());
    assertThat(r.jsonPath().getString("serviceBay.id")).isEqualTo(newBay);
    Map<String, Object> bay = reservation(id, "service_bay_id");
    Map<String, Object> technician = reservation(id, "technician_id");
    assertThat(bay.get("resource")).isEqualTo(newBay);
    assertThat(instant(bay.get("start_at"))).isEqualTo(WED_ONE);
    assertThat(instant(bay.get("end_at"))).isEqualTo(WED_ONE.plus(Duration.ofMinutes(45)));
    assertThat(instant(technician.get("start_at"))).isEqualTo(WED_ONE);
    assertThat(instant(technician.get("end_at"))).isEqualTo(WED_ONE.plus(Duration.ofMinutes(45)));
    assertThat(
            jdbc.queryForObject(
                "SELECT aggregate_version FROM outbox_event"
                    + " WHERE event_type = 'AppointmentRescheduled' AND aggregate_id = ?::uuid",
                Long.class,
                id))
        .isEqualTo(1L);
    NoDoubleBookingAssert.verify(client);
  }

  @Test
  void staleVersion412() {
    String id = soloJob(WED_NINE, Fixtures.ALICE, Fixtures.ALICE_FOCUS);

    List<Response> responses =
        ConcurrencyHarness.race(
            2,
            i ->
                Dispatch.patch(
                    Fixtures.SOLO,
                    id,
                    "\"0\"",
                    Map.of("start", (i == 0 ? WED_ELEVEN : WED_ONE).toString())));

    assertThat(responses.stream().map(Response::statusCode)).containsExactlyInAnyOrder(200, 412);
    Response loser =
        responses.stream().filter(r -> r.statusCode() == 412).findFirst().orElseThrow();
    assertThat(loser.jsonPath().getString("code")).isEqualTo("VERSION_CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM appointment WHERE id = ?::uuid", Long.class, id))
        .isEqualTo(1L);
    NoDoubleBookingAssert.verify(client);
  }

  @Test
  void dragOntoBookedSlot409WithConflicts() {
    String alice = soloJob(WED_NINE, Fixtures.ALICE, Fixtures.ALICE_FOCUS);
    String bob = soloJob(WED_ELEVEN, Fixtures.BOB, Fixtures.BOB_GOLF);

    Response r =
        Dispatch.patch(
            Fixtures.SOLO, bob, "\"0\"", Map.of("start", WED_NINE.plusSeconds(15 * 60).toString()));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.jsonPath().getString("code")).isEqualTo("SLOT_UNAVAILABLE");
    assertThat(r.jsonPath().getList("conflicts", String.class)).containsExactly(alice);
    assertThat(r.jsonPath().getString("detail"))
        .isEqualTo(
            "The chosen bay or technician is not free at 09:15 on "
                + WEDNESDAY.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
                + " (Europe/London).");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM appointment WHERE id = ?::uuid", Long.class, bob))
        .isZero();
    NoDoubleBookingAssert.verify(client);
  }

  private String bobHoldsNine() {
    Response hold =
        Holds.hold(
            Fixtures.BOB_SUB, Fixtures.SOLO, Fixtures.BOB_GOLF, WED_NINE, Fixtures.OIL_CHANGE);
    assertThat(hold.statusCode()).as(hold.asString()).isEqualTo(201);
    return hold.jsonPath().getString("id");
  }

  @Test
  void dragOntoHeldSlot409SlotHeld() {
    String alice = soloJob(WED_ELEVEN, Fixtures.ALICE, Fixtures.ALICE_FOCUS);
    bobHoldsNine();

    Response r =
        Dispatch.patch(Fixtures.SOLO, alice, "\"0\"", Map.of("start", WED_NINE.toString()));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.jsonPath().getString("code")).isEqualTo("SLOT_HELD");
    assertThat(r.jsonPath().getString("detail"))
        .startsWith("A customer is holding this time until ")
        .endsWith(" (Europe/London). Resend with overrideHold=true to take it.");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM appointment WHERE id = ?::uuid", Long.class, alice))
        .isZero();
  }

  @Test
  void overrideHoldSucceeds() {
    String alice = soloJob(WED_ELEVEN, Fixtures.ALICE, Fixtures.ALICE_FOCUS);
    final String holdId = bobHoldsNine();

    Map<String, Object> body = new HashMap<>();
    body.put("start", WED_NINE.toString());
    body.put("overrideHold", true);
    Response r = Dispatch.patch(Fixtures.SOLO, alice, "\"0\"", body);

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    Holds.get(Fixtures.BOB_SUB, holdId).then().statusCode(404);
    Response confirm =
        Holds.confirm(
            Fixtures.BOB_SUB,
            holdId,
            Fixtures.SOLO,
            Fixtures.BOB_GOLF,
            WED_NINE,
            Fixtures.OIL_CHANGE);
    assertThat(confirm.statusCode()).as(confirm.asString()).isEqualTo(409);
    assertThat(confirm.jsonPath().getString("code")).isEqualTo("SLOT_UNAVAILABLE");
    NoDoubleBookingAssert.verify(client);
  }

  @Test
  void dispatcherBookingRespectsHoldsUnlessOverridden() {
    final String holdId = bobHoldsNine();

    Response refused =
        Dispatch.book(
            Fixtures.SOLO,
            Fixtures.ALICE,
            Fixtures.ALICE_FOCUS,
            WED_NINE,
            null,
            null,
            false,
            Fixtures.OIL_CHANGE);
    assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(409);
    assertThat(refused.jsonPath().getString("code")).isEqualTo("SLOT_HELD");

    Response taken =
        Dispatch.book(
            Fixtures.SOLO,
            Fixtures.ALICE,
            Fixtures.ALICE_FOCUS,
            WED_NINE,
            null,
            null,
            true,
            Fixtures.OIL_CHANGE);
    assertThat(taken.statusCode()).as(taken.asString()).isEqualTo(201);
    Response confirm =
        Holds.confirm(
            Fixtures.BOB_SUB,
            holdId,
            Fixtures.SOLO,
            Fixtures.BOB_GOLF,
            WED_NINE,
            Fixtures.OIL_CHANGE);
    assertThat(confirm.statusCode()).as(confirm.asString()).isEqualTo(409);
    NoDoubleBookingAssert.verify(client);
  }

  @Test
  void unqualifiedTechnician422() {
    Response booked =
        Dispatch.book(
            Fixtures.RIVERSIDE,
            Fixtures.ALICE,
            Fixtures.ALICE_FOCUS,
            WED_NINE,
            null,
            Fixtures.SAM,
            false,
            Fixtures.BRAKE_INSPECTION);
    String id = booked.jsonPath().getString("id");

    Response r =
        Dispatch.patch(
            Fixtures.RIVERSIDE, id, "\"0\"", Map.of("technicianId", Fixtures.LEE.toString()));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(422);
    assertThat(r.jsonPath().getString("code")).isEqualTo("TECHNICIAN_NOT_QUALIFIED");
    assertThat(r.jsonPath().getString("detail"))
        .isEqualTo("Lee does not have the BRAKES skill required by Brake inspection.");
  }

  @Test
  void inProgressStartChange422StartFixed() {
    String id = soloJob(WED_NINE, Fixtures.ALICE, Fixtures.ALICE_FOCUS);
    Dispatch.acted(Fixtures.SOLO, id, "check-in", null);
    Dispatch.acted(Fixtures.SOLO, id, "start", null);

    Response r = Dispatch.patch(Fixtures.SOLO, id, "\"2\"", Map.of("start", WED_ELEVEN.toString()));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(422);
    assertThat(r.jsonPath().getString("code")).isEqualTo("START_FIXED");
    Response resized = Dispatch.patch(Fixtures.SOLO, id, "\"2\"", Map.of("durationMinutes", 90));
    assertThat(resized.statusCode()).as(resized.asString()).isEqualTo(200);
    assertThat(resized.jsonPath().getString("end"))
        .isEqualTo(WED_NINE.plus(Duration.ofMinutes(90)).toString());
    NoDoubleBookingAssert.verify(client);
  }

  @Test
  void extendPastClosingAllowed() {
    Instant fivePm = at(WEDNESDAY, 17, 0, LONDON);
    String id = soloJob(fivePm, Fixtures.ALICE, Fixtures.ALICE_FOCUS);

    Response r = Dispatch.patch(Fixtures.SOLO, id, "\"0\"", Map.of("durationMinutes", 120));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("end")).isEqualTo(at(WEDNESDAY, 19, 0, LONDON).toString());
    assertThat(instant(reservation(id, "technician_id").get("end_at")))
        .isEqualTo(at(WEDNESDAY, 19, 0, LONDON));
    NoDoubleBookingAssert.verify(client);
  }

  @Test
  void dragOutsideOpeningHours422() {
    String id = soloJob(WED_NINE, Fixtures.ALICE, Fixtures.ALICE_FOCUS);

    Response r =
        Dispatch.patch(
            Fixtures.SOLO, id, "\"0\"", Map.of("start", at(WEDNESDAY, 19, 0, LONDON).toString()));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(422);
    assertThat(r.jsonPath().getString("code")).isEqualTo("OUTSIDE_OPENING_HOURS");
  }

  @Test
  void missingIfMatch428() {
    String id = soloJob(WED_NINE, Fixtures.ALICE, Fixtures.ALICE_FOCUS);

    Response r = Dispatch.patch(Fixtures.SOLO, id, null, Map.of("durationMinutes", 60));

    assertThat(r.statusCode()).isEqualTo(428);
    assertThat(r.jsonPath().getString("code")).isEqualTo("PRECONDITION_REQUIRED");
  }

  @Test
  void plainJsonContentTypeIs415() {
    String id = soloJob(WED_NINE, Fixtures.ALICE, Fixtures.ALICE_FOCUS);

    Response r =
        Dispatch.as(Fixtures.SOLO)
            .header("If-Match", "\"0\"")
            .contentType(ContentType.JSON)
            .body(Map.of("durationMinutes", 60))
            .patch(Dispatch.APPOINTMENT, Fixtures.SOLO, id);

    assertThat(r.statusCode()).isEqualTo(415);
    assertThat(r.contentType()).startsWith("application/problem+json");
    assertThat(r.jsonPath().getString("code")).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    assertThat(r.jsonPath().getString("detail")).contains("application/merge-patch+json");
  }

  @Test
  void getSingleAppointmentReturnsEtag() {
    String id = soloJob(WED_NINE, Fixtures.ALICE, Fixtures.ALICE_FOCUS);
    Dispatch.patch(Fixtures.SOLO, id, "\"0\"", Map.of("durationMinutes", 60));

    Response r = Dispatch.get(Fixtures.SOLO, id);

    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.header("ETag")).isEqualTo("\"1\"");
    assertThat(r.header("Cache-Control")).contains("no-store");
    assertThat(r.jsonPath().getString("id")).isEqualTo(id);
    assertThat(r.jsonPath().getString("end"))
        .isEqualTo(WED_NINE.plus(Duration.ofMinutes(60)).toString());
    Dispatch.as(Fixtures.RIVERSIDE)
        .get(Dispatch.APPOINTMENT, Fixtures.RIVERSIDE, id)
        .then()
        .statusCode(404);
    Dispatch.as(Fixtures.SOLO)
        .get(Dispatch.APPOINTMENT, Fixtures.RIVERSIDE, id)
        .then()
        .statusCode(403);
  }
}
