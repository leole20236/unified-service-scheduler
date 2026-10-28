package com.keyloop.scheduler.booking;

import static com.keyloop.scheduler.support.Bookings.LONDON;
import static com.keyloop.scheduler.support.Bookings.at;
import static com.keyloop.scheduler.support.Bookings.body;
import static com.keyloop.scheduler.support.Bookings.book;
import static com.keyloop.scheduler.support.Bookings.next;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.support.Bookings;
import com.keyloop.scheduler.support.Bookings.Customer;
import com.keyloop.scheduler.support.ConcurrencyHarness;
import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Headers;
import com.keyloop.scheduler.support.Holds;
import com.keyloop.scheduler.support.IntegrationTest;
import com.keyloop.scheduler.support.NoDoubleBookingAssert;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Cancel and reschedule racing other writers: the database never double-books. */
@Tag("concurrency")
class CustomerChangeConcurrencyIT extends IntegrationTest {

  private static final String ME = "/api/v1/me/appointments";
  private static final LocalDate WEDNESDAY = next(DayOfWeek.WEDNESDAY);
  private static final Instant WED_NINE = at(WEDNESDAY, 9, 0, LONDON);

  private JdbcClient client;

  @BeforeEach
  void configure() {
    RestAssured.port = port;
    client = JdbcClient.create(jdbc);
  }

  private static String aliceBooksSolo() {
    Response r =
        book(
            Fixtures.ALICE_SUB,
            body(Fixtures.SOLO, Fixtures.ALICE_FOCUS, WED_NINE, Fixtures.OIL_CHANGE),
            "k-" + UUID.randomUUID());
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(201);
    return r.jsonPath().getString("id");
  }

  @RepeatedTest(5)
  void cancelRacingTwentyBookersLeavesAtMostOneOccupant() {
    String id = aliceBooksSolo();
    List<Customer> others = Bookings.newCustomers(jdbc, 20);

    List<Response> responses =
        ConcurrencyHarness.race(
            21,
            i -> {
              if (i == 0) {
                return given()
                    .headers(Headers.customer(Fixtures.ALICE_SUB))
                    .post(ME + "/" + id + "/cancel");
              }
              Customer c = others.get(i - 1);
              return book(
                  c,
                  body(Fixtures.SOLO, c.vehicleId(), WED_NINE, Fixtures.OIL_CHANGE),
                  "race-" + i + "-" + UUID.randomUUID());
            });

    assertThat(responses).hasSize(21);
    List<Response> cancels =
        responses.stream().filter(r -> r.asString().contains("CANCELLED")).toList();
    assertThat(cancels).hasSize(1);
    assertThat(cancels.get(0).statusCode()).isEqualTo(200);
    List<Response> created = responses.stream().filter(r -> r.statusCode() == 201).toList();
    assertThat(created.size()).isLessThanOrEqualTo(1);
    for (Response r : responses) {
      if (r.statusCode() != 200 && r.statusCode() != 201) {
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
        assertThat(r.jsonPath().getString("code")).isEqualTo("SLOT_UNAVAILABLE");
      }
    }
    TimeRange slot = new TimeRange(WED_NINE, WED_NINE.plusSeconds(45 * 60));
    assertThat(NoDoubleBookingAssert.countActive(client, Fixtures.SOLO_BAY, slot))
        .isEqualTo(created.size());
    assertThat(NoDoubleBookingAssert.countActive(client, Fixtures.SOLO_TECH, slot))
        .isEqualTo(created.size());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM appointment WHERE status = 'BOOKED'", Integer.class))
        .isEqualTo(created.size());
    NoDoubleBookingAssert.verify(client);
  }

  @RepeatedTest(5)
  void twoReschedulesWithTheSameIfMatchOneWinsOneGets412() {
    String id = aliceBooksSolo();
    Instant thursday = at(WEDNESDAY.plusDays(1), 11, 0, LONDON);
    Instant friday = at(WEDNESDAY.plusDays(2), 11, 0, LONDON);
    // One hold per user and day: two holds on different days.
    List<String> holds =
        List.of(
            Holds.holdToReschedule(
                    Fixtures.ALICE_SUB,
                    id,
                    Fixtures.SOLO,
                    Fixtures.ALICE_FOCUS,
                    thursday,
                    Fixtures.OIL_CHANGE)
                .jsonPath()
                .getString("id"),
            Holds.holdToReschedule(
                    Fixtures.ALICE_SUB,
                    id,
                    Fixtures.SOLO,
                    Fixtures.ALICE_FOCUS,
                    friday,
                    Fixtures.OIL_CHANGE)
                .jsonPath()
                .getString("id"));

    List<Response> responses =
        ConcurrencyHarness.race(
            2,
            i ->
                given()
                    .headers(Headers.customer(Fixtures.ALICE_SUB))
                    .header("If-Match", "\"0\"")
                    .contentType(ContentType.JSON)
                    .body(Map.of("holdId", holds.get(i)))
                    .post(ME + "/" + id + "/reschedule"));

    assertThat(responses.stream().map(Response::statusCode)).containsExactlyInAnyOrder(200, 412);
    Response loser =
        responses.stream().filter(r -> r.statusCode() == 412).findFirst().orElseThrow();
    assertThat(loser.jsonPath().getString("code")).isEqualTo("VERSION_CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM appointment WHERE id = ?::uuid", Long.class, id))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM resource_reservation WHERE appointment_id = ?::uuid"
                    + " AND active",
                Integer.class,
                id))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'AppointmentRescheduled'",
                Integer.class))
        .isEqualTo(1);
    NoDoubleBookingAssert.verify(client);
  }

  @RepeatedTest(5)
  void cancelRacingRescheduleLeavesNoPhantomOccupancy() {
    String id = aliceBooksSolo();
    Instant thursday = at(WEDNESDAY.plusDays(1), 11, 0, LONDON);
    String hold =
        Holds.holdToReschedule(
                Fixtures.ALICE_SUB,
                id,
                Fixtures.SOLO,
                Fixtures.ALICE_FOCUS,
                thursday,
                Fixtures.OIL_CHANGE)
            .jsonPath()
            .getString("id");

    List<Response> responses =
        ConcurrencyHarness.race(
            2,
            i ->
                i == 0
                    ? given()
                        .headers(Headers.customer(Fixtures.ALICE_SUB))
                        .post(ME + "/" + id + "/cancel")
                    : given()
                        .headers(Headers.customer(Fixtures.ALICE_SUB))
                        .header("If-Match", "\"0\"")
                        .contentType(ContentType.JSON)
                        .body(Map.of("holdId", hold))
                        .post(ME + "/" + id + "/reschedule"));

    // Whatever the order, the appointment ends cancelled with nothing reserved, and the
    // cancellation event describes the slot that was actually cancelled.
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM appointment WHERE id = ?::uuid", String.class, id))
        .isEqualTo("CANCELLED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM resource_reservation WHERE active", Integer.class))
        .isZero();
    String eventStart =
        jdbc.queryForObject(
            "SELECT payload->'data'->>'start' FROM outbox_event"
                + " WHERE event_type = 'AppointmentCancelled'",
            String.class);
    Instant cancelledStart =
        jdbc.queryForObject(
                "SELECT start_at FROM appointment WHERE id = ?::uuid", java.sql.Timestamp.class, id)
            .toInstant();
    assertThat(Instant.parse(eventStart)).isEqualTo(cancelledStart);
    assertThat(responses.stream().map(Response::statusCode)).contains(200);
    NoDoubleBookingAssert.verify(client);
  }
}
