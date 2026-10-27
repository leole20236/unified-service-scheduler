package com.keyloop.scheduler.hold;

import static com.keyloop.scheduler.support.Bookings.LONDON;
import static com.keyloop.scheduler.support.Bookings.at;
import static com.keyloop.scheduler.support.Bookings.next;
import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.support.Bookings;
import com.keyloop.scheduler.support.Bookings.Customer;
import com.keyloop.scheduler.support.ConcurrencyHarness;
import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Holds;
import com.keyloop.scheduler.support.IntegrationTest;
import com.keyloop.scheduler.support.NoDoubleBookingAssert;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Races holds against holds, confirms and direct bookings; nothing is ever double-booked. */
@Tag("concurrency")
class HoldConcurrencyIT extends IntegrationTest {

  private static final LocalDate WEDNESDAY = next(DayOfWeek.WEDNESDAY);
  private static final Instant NINE = at(WEDNESDAY, 9, 0, LONDON);

  @Autowired StringRedisTemplate redis;
  private JdbcClient client;

  @BeforeEach
  void configure() {
    RestAssured.port = port;
    client = JdbcClient.create(jdbc);
  }

  private int appointments() {
    return jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class);
  }

  private static long status(List<Response> responses, int code) {
    return responses.stream().filter(r -> r.statusCode() == code).count();
  }

  /** Every response is a 201 or a 409 SLOT_UNAVAILABLE: no 500s, 503s or deadlocks. */
  private static void onlyCreatedOrSlotUnavailable(List<Response> responses) {
    for (Response r : responses) {
      if (r.statusCode() != 201) {
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
        assertThat(r.jsonPath().getString("code")).isEqualTo("SLOT_UNAVAILABLE");
      }
    }
  }

  private static Response hold(Customer c, Instant start) {
    return Holds.hold(c.subject(), Fixtures.SOLO, c.vehicleId(), start, Fixtures.OIL_CHANGE);
  }

  private static Response confirm(Customer c, String holdId, Instant start) {
    return Holds.confirm(
        c.subject(), holdId, Fixtures.SOLO, c.vehicleId(), start, Fixtures.OIL_CHANGE);
  }

  private static Response direct(Customer c, Instant start) {
    return Bookings.book(
        c,
        Bookings.body(Fixtures.SOLO, c.vehicleId(), start, Fixtures.OIL_CHANGE),
        "direct-" + UUID.randomUUID());
  }

  @RepeatedTest(5)
  void fiftyHoldsSameSlotExactlyOneGranted() {
    List<Customer> customers = Bookings.newCustomers(jdbc, 50);

    List<Response> responses = ConcurrencyHarness.race(50, i -> hold(customers.get(i), NINE));

    onlyCreatedOrSlotUnavailable(responses);
    assertThat(status(responses, 201)).isEqualTo(1);
    assertThat(status(responses, 409)).isEqualTo(49);
    assertThat(appointments()).isZero();
    Response granted = responses.stream().filter(r -> r.statusCode() == 201).findFirst().get();
    Customer winner =
        customers.stream()
            .filter(
                c -> redis.hasKey(LedgerKeys.of(Fixtures.SOLO, WEDNESDAY).userHold(c.subject())))
            .findFirst()
            .orElseThrow();
    confirm(winner, granted.jsonPath().getString("id"), NINE).then().statusCode(201);
    assertThat(appointments()).isEqualTo(1);
    NoDoubleBookingAssert.verify(client);
  }

  @RepeatedTest(5)
  void holdersConfirmInParallel() {
    List<Customer> customers = Bookings.newCustomers(jdbc, 20);
    List<Instant> starts = new ArrayList<>();
    for (LocalDate day : List.of(WEDNESDAY, WEDNESDAY.plusDays(1))) {
      for (int slot = 0; slot < 10; slot++) {
        starts.add(at(day, 8, 0, LONDON).plusSeconds(slot * 45L * 60));
      }
    }
    List<String> holdIds = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      Response held = hold(customers.get(i), starts.get(i));
      assertThat(held.statusCode()).as(held.asString()).isEqualTo(201);
      holdIds.add(held.jsonPath().getString("id"));
    }

    List<Response> responses =
        ConcurrencyHarness.race(20, i -> confirm(customers.get(i), holdIds.get(i), starts.get(i)));

    assertThat(responses)
        .allSatisfy(r -> assertThat(r.statusCode()).as(r.asString()).isEqualTo(201));
    assertThat(appointments()).isEqualTo(20);
    NoDoubleBookingAssert.verify(client);
  }

  @RepeatedTest(5)
  void holdVersusDirectBookingRace() {
    List<Customer> customers = Bookings.newCustomers(jdbc, 50);

    List<Response> responses =
        ConcurrencyHarness.race(
            50,
            i -> {
              Customer c = customers.get(i);
              if (i % 2 == 1) {
                return direct(c, NINE);
              }
              Response held = hold(c, NINE);
              return held.statusCode() == 201
                  ? confirm(c, held.jsonPath().getString("id"), NINE)
                  : held;
            });

    onlyCreatedOrSlotUnavailable(responses);
    assertThat(status(responses, 201)).isEqualTo(1);
    assertThat(appointments()).isEqualTo(1);
    NoDoubleBookingAssert.verify(client);
  }

  @RepeatedTest(5)
  void confirmAfterLedgerFlushRace() {
    List<Customer> customers = Bookings.newCustomers(jdbc, 21);
    Customer holder = customers.get(0);
    Response held = hold(holder, NINE);
    assertThat(held.statusCode()).as(held.asString()).isEqualTo(201);
    redis.execute(
        (org.springframework.data.redis.core.RedisCallback<Void>)
            c -> {
              c.serverCommands().flushAll();
              return null;
            });

    List<Response> responses =
        ConcurrencyHarness.race(
            21,
            i ->
                i == 0
                    ? confirm(holder, held.jsonPath().getString("id"), NINE)
                    : direct(customers.get(i), NINE));

    onlyCreatedOrSlotUnavailable(responses);
    assertThat(status(responses, 201)).isEqualTo(1);
    assertThat(appointments()).isEqualTo(1);
    NoDoubleBookingAssert.verify(client);
  }
}
