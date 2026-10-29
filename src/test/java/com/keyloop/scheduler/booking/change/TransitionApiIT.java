package com.keyloop.scheduler.booking.change;

import static com.keyloop.scheduler.support.Bookings.LONDON;
import static com.keyloop.scheduler.support.Bookings.at;
import static com.keyloop.scheduler.support.Bookings.next;
import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.booking.domain.Occupancy;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.hold.SlotLedger;
import com.keyloop.scheduler.support.ConcurrencyHarness;
import com.keyloop.scheduler.support.Dispatch;
import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Headers;
import com.keyloop.scheduler.support.IntegrationTest;
import com.keyloop.scheduler.support.NoDoubleBookingAssert;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lifecycle actions end to end. Solo and Riverside are opened around the clock for these tests so
 * that jobs can run "now" whatever the time of the test run.
 */
class TransitionApiIT extends IntegrationTest {

  private static final LocalDate WEDNESDAY = next(DayOfWeek.WEDNESDAY);
  private static final Instant WED_NINE = at(WEDNESDAY, 9, 0, LONDON);

  @Autowired private SlotLedger ledger;
  @Autowired private CatalogService catalogs;

  private JdbcClient client;

  @BeforeEach
  void configure() {
    RestAssured.port = port;
    client = JdbcClient.create(jdbc);
    Dispatch.alwaysOpen(jdbc, Fixtures.SOLO);
    Dispatch.alwaysOpen(jdbc, Fixtures.RIVERSIDE);
  }

  @AfterEach
  void restoreHours() {
    Dispatch.restoreSeedHours(jdbc);
  }

  /** A Solo job of the given services, moved to start {@code minutesAgo} minutes before now. */
  private String soloJobStarted(int minutesAgo, UUID... services) {
    String id =
        Dispatch.booked(Fixtures.SOLO, Fixtures.ALICE, Fixtures.ALICE_FOCUS, WED_NINE, services);
    Dispatch.shift(jdbc, id, Dispatch.dbNow(jdbc).minus(Duration.ofMinutes(minutesAgo)));
    return id;
  }

  private Map<String, Object> row(String id) {
    return jdbc.queryForMap(
        "SELECT status, version, worked_minutes, started_at, hold_reason, start_at, end_at"
            + " FROM appointment WHERE id = ?::uuid",
        id);
  }

  private List<Map<String, Object>> activeReservations(String id) {
    return jdbc.queryForList(
        "SELECT service_bay_id, technician_id, start_at, end_at FROM resource_reservation"
            + " WHERE appointment_id = ?::uuid AND active ORDER BY service_bay_id NULLS LAST",
        id);
  }

  private static Instant instant(Object timestamp) {
    return ((Timestamp) timestamp).toInstant();
  }

  private static Response walkIn(UUID bay, UUID technician, Instant start) {
    return Dispatch.book(
        Fixtures.RIVERSIDE,
        Fixtures.BOB,
        Fixtures.BOB_GOLF,
        start,
        bay,
        technician,
        false,
        Fixtures.OIL_CHANGE);
  }

  @Test
  void fullHappyPath() {
    String id = soloJobStarted(10, Fixtures.OIL_CHANGE);

    Response checkIn = Dispatch.action(Fixtures.SOLO, id, "check-in", "\"0\"", null);
    assertThat(checkIn.statusCode()).as(checkIn.asString()).isEqualTo(200);
    assertThat(checkIn.header("ETag")).isEqualTo("\"1\"");
    assertThat(checkIn.jsonPath().getString("status")).isEqualTo("CHECKED_IN");
    assertThat(Dispatch.acted(Fixtures.SOLO, id, "start", null).jsonPath().getString("status"))
        .isEqualTo("IN_PROGRESS");
    assertThat(row(id).get("started_at")).isNotNull();
    Response hold = Dispatch.acted(Fixtures.SOLO, id, "hold", Map.of("holdReason", "PARTS"));
    assertThat(hold.jsonPath().getString("status")).isEqualTo("ON_HOLD");
    assertThat(row(id)).containsEntry("hold_reason", "PARTS");
    Response resume = Dispatch.acted(Fixtures.SOLO, id, "resume", null);
    assertThat(resume.jsonPath().getString("status")).isEqualTo("IN_PROGRESS");
    assertThat(row(id)).containsEntry("hold_reason", null);
    NoDoubleBookingAssert.verify(client);
    Response complete = Dispatch.acted(Fixtures.SOLO, id, "complete", null);
    assertThat(complete.jsonPath().getString("status")).isEqualTo("COMPLETED");
    assertThat(complete.header("ETag")).isEqualTo("\"5\"");

    assertThat(
            jdbc.queryForList(
                "SELECT payload->'data'->>'to' FROM outbox_event"
                    + " WHERE aggregate_id = ?::uuid AND event_type = 'AppointmentStatusChanged'"
                    + " ORDER BY aggregate_version",
                String.class,
                id))
        .containsExactly("CHECKED_IN", "IN_PROGRESS", "ON_HOLD", "IN_PROGRESS", "COMPLETED");
    assertThat(
            jdbc.queryForList(
                "SELECT aggregate_version FROM outbox_event WHERE aggregate_id = ?::uuid"
                    + " AND event_type = 'AppointmentStatusChanged' ORDER BY aggregate_version",
                Long.class,
                id))
        .containsExactly(1L, 2L, 3L, 4L, 5L);
    NoDoubleBookingAssert.verify(client);
  }

  /** A Riverside job on Bay 1 with Sam, started 20 minutes ago and now in progress. */
  private String riversideJobInProgress() {
    Response booked =
        Dispatch.book(
            Fixtures.RIVERSIDE,
            Fixtures.ALICE,
            Fixtures.ALICE_FOCUS,
            WED_NINE,
            Fixtures.RIVERSIDE_BAY_1,
            Fixtures.SAM,
            false,
            Fixtures.OIL_CHANGE);
    String id = booked.jsonPath().getString("id");
    Dispatch.shift(jdbc, id, Dispatch.dbNow(jdbc).minus(Duration.ofMinutes(20)));
    Dispatch.acted(Fixtures.RIVERSIDE, id, "check-in", null);
    Dispatch.acted(Fixtures.RIVERSIDE, id, "start", null);
    return id;
  }

  @Test
  void holdFreesTechnicianKeepsBay() {
    String id = riversideJobInProgress();

    Dispatch.acted(Fixtures.RIVERSIDE, id, "hold", Map.of("holdReason", "PARTS"));

    Instant soon = Dispatch.dbNow(jdbc).plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS);
    Response onSam = walkIn(Fixtures.RIVERSIDE_BAY_2, Fixtures.SAM, soon);
    assertThat(onSam.statusCode()).as(onSam.asString()).isEqualTo(201);
    Response onBay = walkIn(Fixtures.RIVERSIDE_BAY_1, Fixtures.LEE, soon);
    assertThat(onBay.statusCode()).as(onBay.asString()).isEqualTo(409);
    assertThat(onBay.jsonPath().getList("conflicts", String.class)).containsExactly(id);
    List<Map<String, Object>> reservations = activeReservations(id);
    assertThat(reservations).hasSize(2);
    assertThat(instant(reservations.get(1).get("end_at"))).isBefore(soon);
    NoDoubleBookingAssert.verify(client);
  }

  @Test
  void holdWithReleaseBayFreesBoth() {
    String id = riversideJobInProgress();

    Dispatch.acted(
        Fixtures.RIVERSIDE,
        id,
        "hold",
        Map.of("holdReason", "CUSTOMER_APPROVAL", "releaseBay", true));

    Instant soon = Dispatch.dbNow(jdbc).plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS);
    Response r = walkIn(Fixtures.RIVERSIDE_BAY_1, Fixtures.SAM, soon);
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(201);
    assertThat(row(id)).containsEntry("hold_reason", "CUSTOMER_APPROVAL");
    NoDoubleBookingAssert.verify(client);
  }

  @Test
  void holdWithoutReasonIs400() {
    String id = soloJobStarted(10, Fixtures.OIL_CHANGE);
    Dispatch.acted(Fixtures.SOLO, id, "check-in", null);
    Dispatch.acted(Fixtures.SOLO, id, "start", null);

    Response missing = Dispatch.action(Fixtures.SOLO, id, "hold", null, Map.of());
    Response unknown =
        Dispatch.action(Fixtures.SOLO, id, "hold", null, Map.of("holdReason", "LUNCH"));
    Response noBody = Dispatch.action(Fixtures.SOLO, id, "hold", null, null);

    assertThat(missing.statusCode()).isEqualTo(400);
    assertThat(missing.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
    assertThat(unknown.statusCode()).isEqualTo(400);
    assertThat(noBody.statusCode()).isIn(400, 415);
    assertThat(row(id)).containsEntry("status", "IN_PROGRESS");
  }

  @Test
  void resumeReservesRemainingMinutes() {
    String id = soloJobStarted(40, Fixtures.BRAKE_INSPECTION);
    Dispatch.acted(Fixtures.SOLO, id, "check-in", null);
    Dispatch.acted(Fixtures.SOLO, id, "start", null);
    jdbc.update(
        "UPDATE appointment SET started_at = now() - interval '30 minutes' WHERE id = ?::uuid", id);
    Dispatch.acted(Fixtures.SOLO, id, "hold", Map.of("holdReason", "PARTS"));
    assertThat(row(id)).containsEntry("worked_minutes", 30);
    Instant resumeAt =
        Dispatch.dbNow(jdbc).plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MINUTES);

    Response r =
        Dispatch.action(Fixtures.SOLO, id, "resume", null, Map.of("resumeAt", resumeAt.toString()));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    Instant end = resumeAt.plus(Duration.ofMinutes(45));
    assertThat(r.jsonPath().getString("end")).isEqualTo(end.toString());
    List<Map<String, Object>> reservations = activeReservations(id);
    assertThat(reservations).hasSize(2);
    for (Map<String, Object> reservation : reservations) {
      assertThat(instant(reservation.get("start_at"))).isEqualTo(resumeAt);
      assertThat(instant(reservation.get("end_at"))).isEqualTo(end);
    }
    Map<String, Object> appointment = row(id);
    assertThat(appointment)
        .containsEntry("status", "IN_PROGRESS")
        .containsEntry("hold_reason", null);
    assertThat(instant(appointment.get("started_at"))).isEqualTo(resumeAt);
    assertThat(instant(appointment.get("end_at"))).isEqualTo(end);
    NoDoubleBookingAssert.verify(client);
  }

  /** A Solo job put on hold now, its bay kept. */
  private String soloJobOnHold() {
    String id = soloJobStarted(10, Fixtures.OIL_CHANGE);
    Dispatch.acted(Fixtures.SOLO, id, "check-in", null);
    Dispatch.acted(Fixtures.SOLO, id, "start", null);
    Dispatch.acted(Fixtures.SOLO, id, "hold", Map.of("holdReason", "PARTS"));
    return id;
  }

  @Test
  void resumeOntoBusyTechnician409() {
    String id = soloJobOnHold();
    String other =
        Dispatch.booked(
            Fixtures.SOLO, Fixtures.BOB, Fixtures.BOB_GOLF, WED_NINE, Fixtures.OIL_CHANGE);

    Response r =
        Dispatch.action(
            Fixtures.SOLO,
            id,
            "resume",
            null,
            Map.of("resumeAt", WED_NINE.plus(Duration.ofMinutes(20)).toString()));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.jsonPath().getString("code")).isEqualTo("SLOT_UNAVAILABLE");
    assertThat(r.jsonPath().getList("conflicts", String.class)).containsExactly(other);
    assertThat(row(id)).containsEntry("status", "ON_HOLD");
    NoDoubleBookingAssert.verify(client);
  }

  @Test
  void resumeOntoUnqualifiedTechnicianOrTooEarlyIsRefused() {
    Response booked =
        Dispatch.book(
            Fixtures.RIVERSIDE,
            Fixtures.ALICE,
            Fixtures.ALICE_FOCUS,
            WED_NINE,
            Fixtures.RIVERSIDE_BAY_1,
            Fixtures.SAM,
            false,
            Fixtures.BRAKE_INSPECTION);
    String id = booked.jsonPath().getString("id");
    Dispatch.shift(jdbc, id, Dispatch.dbNow(jdbc).minus(Duration.ofMinutes(20)));
    Dispatch.acted(Fixtures.RIVERSIDE, id, "check-in", null);
    Dispatch.acted(Fixtures.RIVERSIDE, id, "start", null);
    Dispatch.acted(Fixtures.RIVERSIDE, id, "hold", Map.of("holdReason", "PARTS"));

    Response lee =
        Dispatch.action(
            Fixtures.RIVERSIDE,
            id,
            "resume",
            null,
            Map.of("technicianId", Fixtures.LEE.toString()));
    Response early =
        Dispatch.action(
            Fixtures.RIVERSIDE,
            id,
            "resume",
            null,
            Map.of("resumeAt", Dispatch.dbNow(jdbc).minus(Duration.ofMinutes(20)).toString()));

    assertThat(lee.statusCode()).as(lee.asString()).isEqualTo(422);
    assertThat(lee.jsonPath().getString("code")).isEqualTo("TECHNICIAN_NOT_QUALIFIED");
    assertThat(early.statusCode()).as(early.asString()).isEqualTo(400);
  }

  @Test
  void holdAfterResumeOnTheNextDayLeavesThatDaysLedger() {
    String id = soloJobOnHold();
    LocalDate today = Dispatch.dbNow(jdbc).atZone(LONDON).toLocalDate();
    LocalDate tomorrow = today.plusDays(1);
    Instant resumeAt = at(tomorrow, 10, 0, LONDON);
    Dispatch.acted(Fixtures.SOLO, id, "resume", Map.of("resumeAt", resumeAt.toString()));
    DealershipView solo = catalogs.get(Fixtures.SOLO).dealership();
    ledger.ensureLoaded(Fixtures.SOLO, today, SlotLedger.dayWindow(solo, today));
    ledger.ensureLoaded(Fixtures.SOLO, tomorrow, SlotLedger.dayWindow(solo, tomorrow));
    TimeRange resumed = new TimeRange(resumeAt, resumeAt.plus(Duration.ofMinutes(45)));
    assertThat(confirmed(tomorrow).isFree(Fixtures.SOLO_TECH, resumed)).isFalse();
    final Occupancy todayBefore = confirmed(today);

    Dispatch.acted(Fixtures.SOLO, id, "hold", Map.of("holdReason", "PARTS"));

    assertThat(confirmed(tomorrow).isFree(Fixtures.SOLO_TECH, resumed)).isTrue();
    assertThat(confirmed(tomorrow).isFree(Fixtures.SOLO_BAY, resumed)).isFalse();
    assertThat(confirmed(today)).isEqualTo(todayBefore);
    NoDoubleBookingAssert.verify(client);
  }

  private Occupancy confirmed(LocalDate date) {
    return ledger.occupancy(Fixtures.SOLO, date, false, null);
  }

  @Test
  void dragOnHoldJobIs409InvalidTransition() {
    String id = soloJobOnHold();

    Response r = Dispatch.patch(Fixtures.SOLO, id, "\"3\"", Map.of("start", WED_NINE.toString()));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.jsonPath().getString("code")).isEqualTo("INVALID_TRANSITION");
  }

  @Test
  void completeEarlyReleasesRemainder() {
    String id = soloJobStarted(10, Fixtures.OIL_CHANGE);
    Dispatch.acted(Fixtures.SOLO, id, "check-in", null);
    Dispatch.acted(Fixtures.SOLO, id, "start", null);
    Instant soon = Dispatch.dbNow(jdbc).plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.SECONDS);
    Response before =
        Dispatch.book(
            Fixtures.SOLO,
            Fixtures.BOB,
            Fixtures.BOB_GOLF,
            soon,
            null,
            null,
            false,
            Fixtures.OIL_CHANGE);
    assertThat(before.statusCode()).as(before.asString()).isEqualTo(409);

    Dispatch.acted(Fixtures.SOLO, id, "complete", null);

    Map<String, Object> appointment = row(id);
    assertThat(instant(appointment.get("end_at"))).isBefore(soon);
    Response after =
        Dispatch.book(
            Fixtures.SOLO,
            Fixtures.BOB,
            Fixtures.BOB_GOLF,
            soon,
            null,
            null,
            false,
            Fixtures.OIL_CHANGE);
    assertThat(after.statusCode()).as(after.asString()).isEqualTo(201);
    NoDoubleBookingAssert.verify(client);
  }

  @Test
  void checkInVsCancelRaceOneWins() {
    String id =
        Dispatch.booked(
            Fixtures.SOLO, Fixtures.ALICE, Fixtures.ALICE_FOCUS, WED_NINE, Fixtures.OIL_CHANGE);

    List<Response> responses =
        ConcurrencyHarness.race(
            2,
            i -> Dispatch.action(Fixtures.SOLO, id, i == 0 ? "check-in" : "cancel", "\"0\"", null));

    assertThat(responses.stream().filter(r -> r.statusCode() == 200)).hasSize(1);
    assertThat(responses.stream().map(Response::statusCode)).containsExactlyInAnyOrder(200, 412);
    assertThat(row(id)).containsEntry("version", 1L);
    NoDoubleBookingAssert.verify(client);
  }

  @Test
  void cancelReleasesTheSlotAndWritesBothEvents() {
    String id =
        Dispatch.booked(
            Fixtures.SOLO, Fixtures.ALICE, Fixtures.ALICE_FOCUS, WED_NINE, Fixtures.OIL_CHANGE);

    Response r =
        Dispatch.action(Fixtures.SOLO, id, "cancel", null, Map.of("reason", "Customer called"));

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.jsonPath().getString("status")).isEqualTo("CANCELLED");
    assertThat(activeReservations(id)).isEmpty();
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM outbox_event WHERE aggregate_id = ?::uuid"
                    + " AND aggregate_version = 1 ORDER BY event_type",
                String.class,
                id))
        .containsExactly("AppointmentCancelled", "AppointmentStatusChanged");
    assertThat(
            jdbc.queryForObject(
                "SELECT payload->'data'->>'reason' FROM outbox_event WHERE aggregate_id = ?::uuid"
                    + " AND event_type = 'AppointmentStatusChanged'",
                String.class,
                id))
        .isEqualTo("Customer called");
    Response rebooked =
        Dispatch.book(
            Fixtures.SOLO,
            Fixtures.BOB,
            Fixtures.BOB_GOLF,
            WED_NINE,
            null,
            null,
            false,
            Fixtures.OIL_CHANGE);
    assertThat(rebooked.statusCode()).as(rebooked.asString()).isEqualTo(201);
    Response tooLong =
        Dispatch.action(Fixtures.SOLO, id, "cancel", null, Map.of("reason", "x".repeat(201)));
    assertThat(tooLong.statusCode()).isEqualTo(400);
  }

  @Test
  void noShowBeforeGrace422() {
    String id = soloJobStarted(10, Fixtures.OIL_CHANGE);

    Response early = Dispatch.action(Fixtures.SOLO, id, "no-show", null, null);

    assertThat(early.statusCode()).as(early.asString()).isEqualTo(422);
    assertThat(early.jsonPath().getString("code")).isEqualTo("NO_SHOW_TOO_EARLY");
    assertThat(early.jsonPath().getString("detail"))
        .startsWith("No-show can be recorded from ")
        .endsWith("(Europe/London), 30 minutes after the start.");
    Dispatch.shift(jdbc, id, Dispatch.dbNow(jdbc).minus(Duration.ofMinutes(31)));
    Response late = Dispatch.action(Fixtures.SOLO, id, "no-show", null, null);
    assertThat(late.statusCode()).as(late.asString()).isEqualTo(200);
    assertThat(late.jsonPath().getString("status")).isEqualTo("NO_SHOW");
    assertThat(activeReservations(id)).isEmpty();
  }

  @Test
  void invalidTransition409() {
    String id =
        Dispatch.booked(
            Fixtures.SOLO, Fixtures.ALICE, Fixtures.ALICE_FOCUS, WED_NINE, Fixtures.OIL_CHANGE);

    Response r = Dispatch.action(Fixtures.SOLO, id, "start", null, null);

    assertThat(r.statusCode()).isEqualTo(409);
    assertThat(r.jsonPath().getString("code")).isEqualTo("INVALID_TRANSITION");
    assertThat(r.jsonPath().getString("detail"))
        .isEqualTo("Cannot start an appointment that is BOOKED; check it in first.");
  }

  @Test
  void actionWithStaleIfMatch412() {
    String id =
        Dispatch.booked(
            Fixtures.SOLO, Fixtures.ALICE, Fixtures.ALICE_FOCUS, WED_NINE, Fixtures.OIL_CHANGE);

    Response r = Dispatch.action(Fixtures.SOLO, id, "check-in", "\"5\"", null);

    assertThat(r.statusCode()).isEqualTo(412);
    assertThat(r.jsonPath().getString("code")).isEqualTo("VERSION_CONFLICT");
    assertThat(row(id)).containsEntry("status", "BOOKED");
  }

  @Test
  void otherDealershipsAppointmentIs404AndCustomersAre403() {
    String id =
        Dispatch.booked(
            Fixtures.SOLO, Fixtures.ALICE, Fixtures.ALICE_FOCUS, WED_NINE, Fixtures.OIL_CHANGE);

    Dispatch.action(Fixtures.RIVERSIDE, id, "check-in", null, null).then().statusCode(404);
    RestAssured.given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .post(Dispatch.APPOINTMENT + "/check-in", Fixtures.SOLO, id)
        .then()
        .statusCode(403);
  }
}
