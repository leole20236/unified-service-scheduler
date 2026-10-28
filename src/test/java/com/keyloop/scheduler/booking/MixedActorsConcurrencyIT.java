package com.keyloop.scheduler.booking;

import static com.keyloop.scheduler.support.Bookings.LONDON;
import static com.keyloop.scheduler.support.Bookings.at;
import static com.keyloop.scheduler.support.Bookings.body;
import static com.keyloop.scheduler.support.Bookings.book;
import static com.keyloop.scheduler.support.Bookings.next;
import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.support.Bookings;
import com.keyloop.scheduler.support.Bookings.Customer;
import com.keyloop.scheduler.support.ConcurrencyHarness;
import com.keyloop.scheduler.support.Dispatch;
import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Holds;
import com.keyloop.scheduler.support.IntegrationTest;
import com.keyloop.scheduler.support.NoDoubleBookingAssert;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Every write path racing for one slot of Solo (one bay, one technician): customer holds and
 * confirms, dispatcher walk-ins, drags, resumes, cancels and blocks. The database never lets two of
 * them occupy the same time.
 */
@Tag("concurrency")
class MixedActorsConcurrencyIT extends IntegrationTest {

  private static final LocalDate WEDNESDAY = next(DayOfWeek.WEDNESDAY);
  private static final Instant WED_NINE = at(WEDNESDAY, 9, 0, LONDON);
  private static final Instant WED_ELEVEN = at(WEDNESDAY, 11, 0, LONDON);
  private static final Instant WED_ONE = at(WEDNESDAY, 13, 0, LONDON);
  private static final Instant WED_TWO = at(WEDNESDAY, 14, 0, LONDON);
  private static final Duration OIL = Duration.ofMinutes(45);
  private static final Set<Integer> EXPECTED = Set.of(200, 201, 409, 412);

  private JdbcClient client;

  @BeforeEach
  void configure() {
    RestAssured.port = port;
    client = JdbcClient.create(jdbc);
  }

  private int occupants(UUID resource, Instant start) {
    return NoDoubleBookingAssert.countActive(
        client, resource, new TimeRange(start, start.plus(OIL)));
  }

  private static void allExpected(List<Response> responses) {
    for (Response r : responses) {
      assertThat(r.statusCode()).as(r.asString()).isIn(EXPECTED);
    }
  }

  private static long count(List<Response> responses, int status) {
    return responses.stream().filter(r -> r.statusCode() == status).count();
  }

  @RepeatedTest(5)
  void allWritePathsRaceForOneSlot() {
    String eleven =
        Dispatch.booked(
            Fixtures.SOLO, Fixtures.ALICE, Fixtures.ALICE_FOCUS, WED_ELEVEN, Fixtures.OIL_CHANGE);
    List<Customer> holders = Bookings.newCustomers(jdbc, 10);
    List<Customer> walkIns = Bookings.newCustomers(jdbc, 10);

    List<Response> responses =
        ConcurrencyHarness.race(
            30,
            i -> {
              if (i < 10) {
                Customer c = holders.get(i);
                Response hold =
                    Holds.hold(
                        c.subject(), Fixtures.SOLO, c.vehicleId(), WED_NINE, Fixtures.OIL_CHANGE);
                if (hold.statusCode() != 201) {
                  return hold;
                }
                return Holds.confirm(
                    c.subject(),
                    hold.jsonPath().getString("id"),
                    Fixtures.SOLO,
                    c.vehicleId(),
                    WED_NINE,
                    Fixtures.OIL_CHANGE);
              }
              if (i < 20) {
                Customer c = walkIns.get(i - 10);
                return Dispatch.book(
                    Fixtures.SOLO,
                    c.id(),
                    c.vehicleId(),
                    WED_NINE,
                    null,
                    null,
                    false,
                    Fixtures.OIL_CHANGE);
              }
              if (i < 25) {
                return Dispatch.patch(
                    Fixtures.SOLO, eleven, "\"0\"", Map.of("start", WED_NINE.toString()));
              }
              return Dispatch.block(
                  Fixtures.SOLO,
                  "BAY",
                  Fixtures.SOLO_BAY,
                  WED_NINE,
                  at(WEDNESDAY, 10, 0, LONDON),
                  "Race " + i,
                  false);
            });

    assertThat(responses).hasSize(30);
    allExpected(responses);
    assertThat(occupants(Fixtures.SOLO_BAY, WED_NINE)).isEqualTo(1);
    assertThat(occupants(Fixtures.SOLO_TECH, WED_NINE)).isLessThanOrEqualTo(1);
    assertThat(count(responses, 200) + count(responses, 201)).isEqualTo(1);
    NoDoubleBookingAssert.verify(client);
  }

  @RepeatedTest(5)
  void twoDispatchersDragDifferentJobsIntoSameGap() {
    String nine =
        Dispatch.booked(
            Fixtures.SOLO, Fixtures.ALICE, Fixtures.ALICE_FOCUS, WED_NINE, Fixtures.OIL_CHANGE);
    String eleven =
        Dispatch.booked(
            Fixtures.SOLO, Fixtures.BOB, Fixtures.BOB_GOLF, WED_ELEVEN, Fixtures.OIL_CHANGE);

    List<Response> responses =
        ConcurrencyHarness.race(
            2,
            i ->
                Dispatch.patch(
                    Fixtures.SOLO,
                    i == 0 ? nine : eleven,
                    "\"0\"",
                    Map.of("start", WED_TWO.toString())));

    assertThat(responses.stream().map(Response::statusCode)).containsExactlyInAnyOrder(200, 409);
    Response loser =
        responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow();
    assertThat(loser.jsonPath().getString("code")).isEqualTo("SLOT_UNAVAILABLE");
    assertThat(loser.jsonPath().getList("conflicts", String.class)).hasSize(1).doesNotContainNull();
    assertThat(occupants(Fixtures.SOLO_BAY, WED_TWO)).isEqualTo(1);
    assertThat(occupants(Fixtures.SOLO_TECH, WED_TWO)).isEqualTo(1);
    NoDoubleBookingAssert.verify(client);
  }

  @RepeatedTest(5)
  void resumeVersusNewBookingRace() {
    String held =
        Dispatch.booked(
            Fixtures.SOLO, Fixtures.ALICE, Fixtures.ALICE_FOCUS, WED_NINE, Fixtures.OIL_CHANGE);
    Dispatch.shift(jdbc, held, Dispatch.dbNow(jdbc).minus(Duration.ofMinutes(10)));
    Dispatch.acted(Fixtures.SOLO, held, "check-in", null);
    Dispatch.acted(Fixtures.SOLO, held, "start", null);
    Dispatch.acted(Fixtures.SOLO, held, "hold", Map.of("holdReason", "PARTS"));
    List<Customer> customers = Bookings.newCustomers(jdbc, 20);

    List<Response> responses =
        ConcurrencyHarness.race(
            21,
            i -> {
              if (i == 0) {
                return Dispatch.action(
                    Fixtures.SOLO, held, "resume", null, Map.of("resumeAt", WED_ONE.toString()));
              }
              Customer c = customers.get(i - 1);
              return book(
                  c,
                  body(Fixtures.SOLO, c.vehicleId(), WED_ONE, Fixtures.OIL_CHANGE),
                  "race-" + i + "-" + UUID.randomUUID());
            });

    allExpected(responses);
    assertThat(count(responses, 200) + count(responses, 201)).isEqualTo(1);
    assertThat(occupants(Fixtures.SOLO_BAY, WED_ONE)).isEqualTo(1);
    assertThat(occupants(Fixtures.SOLO_TECH, WED_ONE)).isEqualTo(1);
    NoDoubleBookingAssert.verify(client);
  }

  @RepeatedTest(5)
  void cancelAndRebookRace() {
    String nine =
        Dispatch.booked(
            Fixtures.SOLO, Fixtures.ALICE, Fixtures.ALICE_FOCUS, WED_NINE, Fixtures.OIL_CHANGE);
    List<Customer> customers = Bookings.newCustomers(jdbc, 20);

    List<Response> responses =
        ConcurrencyHarness.race(
            21,
            i -> {
              if (i == 0) {
                return Dispatch.action(Fixtures.SOLO, nine, "cancel", null, null);
              }
              Customer c = customers.get(i - 1);
              return book(
                  c,
                  body(Fixtures.SOLO, c.vehicleId(), WED_NINE, Fixtures.OIL_CHANGE),
                  "race-" + i + "-" + UUID.randomUUID());
            });

    allExpected(responses);
    assertThat(count(responses, 200)).as("the cancel always succeeds").isEqualTo(1);
    long created = count(responses, 201);
    assertThat(created).isLessThanOrEqualTo(1);
    assertThat(occupants(Fixtures.SOLO_BAY, WED_NINE)).isEqualTo((int) created);
    assertThat(occupants(Fixtures.SOLO_TECH, WED_NINE)).isEqualTo((int) created);
    if (created == 0) {
      // Every booker came before the cancel committed: the freed slot must really be free.
      Customer late = Bookings.newCustomer(jdbc);
      Response r =
          book(late, body(Fixtures.SOLO, late.vehicleId(), WED_NINE, Fixtures.OIL_CHANGE), "late");
      assertThat(r.statusCode()).as(r.asString()).isEqualTo(201);
    }
    NoDoubleBookingAssert.verify(client);
  }
}
