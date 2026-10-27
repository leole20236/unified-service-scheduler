package com.keyloop.scheduler.booking;

import static com.keyloop.scheduler.support.Bookings.LONDON;
import static com.keyloop.scheduler.support.Bookings.NEW_YORK;
import static com.keyloop.scheduler.support.Bookings.at;
import static com.keyloop.scheduler.support.Bookings.body;
import static com.keyloop.scheduler.support.Bookings.book;
import static com.keyloop.scheduler.support.Bookings.next;
import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.support.Bookings;
import com.keyloop.scheduler.support.Bookings.Customer;
import com.keyloop.scheduler.support.ConcurrencyHarness;
import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.IntegrationTest;
import com.keyloop.scheduler.support.NoDoubleBookingAssert;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Races many simultaneous bookings and proves that no resource is ever double-booked. */
@Tag("concurrency")
class BookingConcurrencyIT extends IntegrationTest {

  private JdbcClient client;

  @BeforeEach
  void configure() {
    RestAssured.port = port;
    client = JdbcClient.create(jdbc);
  }

  private static String key(int i) {
    return "race-" + i + "-" + UUID.randomUUID();
  }

  private static List<Response> created(List<Response> responses) {
    return responses.stream().filter(r -> r.statusCode() == 201).toList();
  }

  /** Every response is a 201 or a 409 SLOT_UNAVAILABLE: no 500s, no other failure modes. */
  private static void onlyCreatedOrSlotUnavailable(List<Response> responses) {
    for (Response r : responses) {
      if (r.statusCode() != 201) {
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
        assertThat(r.jsonPath().getString("code")).isEqualTo("SLOT_UNAVAILABLE");
      }
    }
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private int activeReservations() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM resource_reservation WHERE active", Integer.class);
  }

  private static TimeRange period(Response r) {
    return new TimeRange(
        Instant.parse(r.jsonPath().getString("start")),
        Instant.parse(r.jsonPath().getString("end")));
  }

  @RepeatedTest(5)
  void fiftyRequestsSameStartExactlyOneWins() {
    List<Customer> customers = Bookings.newCustomers(jdbc, 50);
    Instant start = at(next(DayOfWeek.WEDNESDAY), 9, 0, LONDON);

    List<Response> responses =
        ConcurrencyHarness.race(
            50,
            i ->
                book(
                    customers.get(i),
                    body(Fixtures.SOLO, customers.get(i).vehicleId(), start, Fixtures.OIL_CHANGE),
                    key(i)));

    assertThat(responses).hasSize(50);
    onlyCreatedOrSlotUnavailable(responses);
    assertThat(created(responses)).hasSize(1);
    assertThat(responses.stream().filter(r -> r.statusCode() == 409)).hasSize(49);
    assertThat(count("appointment")).isEqualTo(1);
    assertThat(activeReservations()).isEqualTo(2);
    assertThat(count("outbox_event")).isEqualTo(1);
    NoDoubleBookingAssert.verify(client);
  }

  @RepeatedTest(5)
  void overlappingDifferentDurationsOnlyOneWins() {
    List<Customer> customers = Bookings.newCustomers(jdbc, 50);
    LocalDate day = next(DayOfWeek.WEDNESDAY);
    Instant oilAtNine = at(day, 9, 0, LONDON);
    Instant brakesAtHalfEight = at(day, 8, 30, LONDON);

    List<Response> responses =
        ConcurrencyHarness.race(
            50,
            i -> {
              Customer c = customers.get(i);
              return book(
                  c,
                  i % 2 == 0
                      ? body(Fixtures.SOLO, c.vehicleId(), oilAtNine, Fixtures.OIL_CHANGE)
                      : body(
                          Fixtures.SOLO,
                          c.vehicleId(),
                          brakesAtHalfEight,
                          Fixtures.BRAKE_INSPECTION),
                  key(i));
            });

    onlyCreatedOrSlotUnavailable(responses);
    assertThat(created(responses)).hasSize(1);
    assertThat(count("appointment")).isEqualTo(1);
    NoDoubleBookingAssert.verify(client);
  }

  @RepeatedTest(5)
  void capacityThreeAdmitsExactlyThree() {
    List<Customer> customers = Bookings.newCustomers(jdbc, 40);
    Instant start = at(next(DayOfWeek.WEDNESDAY), 10, 0, LONDON);

    List<Response> responses =
        ConcurrencyHarness.race(
            40,
            i ->
                book(
                    customers.get(i),
                    body(
                        Fixtures.RIVERSIDE,
                        customers.get(i).vehicleId(),
                        start,
                        Fixtures.BRAKE_INSPECTION),
                    key(i)));

    onlyCreatedOrSlotUnavailable(responses);
    List<Response> created = created(responses);
    assertThat(created).hasSize(3);
    assertThat(created.stream().map(r -> r.jsonPath().getString("technician.id")))
        .containsExactlyInAnyOrder(
            Fixtures.SAM.toString(), Fixtures.ANA.toString(), Fixtures.TOM.toString());
    assertThat(created.stream().map(r -> r.jsonPath().getString("serviceBay.id")))
        .containsExactlyInAnyOrder(
            Fixtures.RIVERSIDE_BAY_1.toString(),
            Fixtures.RIVERSIDE_BAY_2.toString(),
            Fixtures.RIVERSIDE_BAY_3.toString());
    NoDoubleBookingAssert.verify(client);
  }

  @RepeatedTest(5)
  void technicianSharedAcrossBaysNotDoubleBooked() {
    List<Customer> customers = Bookings.newCustomers(jdbc, 30);
    Instant start = at(next(DayOfWeek.WEDNESDAY), 10, 0, LONDON);

    List<Response> responses =
        ConcurrencyHarness.race(
            30,
            i ->
                book(
                    customers.get(i),
                    body(
                        Fixtures.RIVERSIDE,
                        customers.get(i).vehicleId(),
                        start,
                        Fixtures.DIAGNOSTIC),
                    key(i)));

    onlyCreatedOrSlotUnavailable(responses);
    List<Response> created = created(responses);
    assertThat(created).hasSize(2);
    assertThat(created.stream().map(r -> r.jsonPath().getString("technician.id")))
        .containsExactlyInAnyOrder(Fixtures.ANA.toString(), Fixtures.TOM.toString());
    TimeRange job = new TimeRange(start, start.plus(Duration.ofMinutes(60)));
    assertThat(NoDoubleBookingAssert.countActive(client, Fixtures.ANA, job)).isEqualTo(1);
    assertThat(NoDoubleBookingAssert.countActive(client, Fixtures.TOM, job)).isEqualTo(1);
    NoDoubleBookingAssert.verify(client);
  }

  @RepeatedTest(5)
  void mixedOverlappingDurationsNeverOverlap() {
    List<Customer> customers = Bookings.newCustomers(jdbc, 100);
    LocalDate day = next(DayOfWeek.WEDNESDAY);
    Random random = new Random();
    List<Instant> starts =
        java.util.stream.IntStream.range(0, 100)
            .mapToObj(i -> at(day, 9, 0, LONDON).plus(Duration.ofMinutes(15L * random.nextInt(13))))
            .toList();
    List<Boolean> longJob =
        java.util.stream.IntStream.range(0, 100).mapToObj(i -> random.nextBoolean()).toList();

    List<Response> responses =
        ConcurrencyHarness.race(
            100,
            i -> {
              Customer c = customers.get(i);
              UUID service = longJob.get(i) ? Fixtures.BRAKE_INSPECTION : Fixtures.OIL_CHANGE;
              return book(c, body(Fixtures.SOLO, c.vehicleId(), starts.get(i), service), key(i));
            });

    onlyCreatedOrSlotUnavailable(responses);
    List<TimeRange> booked = created(responses).stream().map(BookingConcurrencyIT::period).toList();
    assertThat(booked).isNotEmpty();
    for (int a = 0; a < booked.size(); a++) {
      for (int b = a + 1; b < booked.size(); b++) {
        assertThat(booked.get(a).overlaps(booked.get(b)))
            .as("%s overlaps %s", booked.get(a), booked.get(b))
            .isFalse();
      }
    }
    assertThat(count("appointment")).isEqualTo(booked.size());
    NoDoubleBookingAssert.verify(client);
  }

  @RepeatedTest(5)
  void bayBufferRespectedUnderRace() {
    List<Customer> customers = Bookings.newCustomers(jdbc, 40);
    LocalDate day = next(DayOfWeek.TUESDAY);
    Bookings.blockBayForDay(jdbc, Fixtures.HUDSON, Fixtures.HUDSON_BAY_2, day, NEW_YORK);
    Instant nine = at(day, 9, 0, NEW_YORK);
    Instant nineFifty = at(day, 9, 50, NEW_YORK);

    List<Response> responses =
        ConcurrencyHarness.race(
            40,
            i -> {
              Customer c = customers.get(i);
              Instant start = i % 2 == 0 ? nine : nineFifty;
              return book(
                  c, body(Fixtures.HUDSON, c.vehicleId(), start, Fixtures.OIL_CHANGE), key(i));
            });

    onlyCreatedOrSlotUnavailable(responses);
    List<Response> created = created(responses);
    // 09:00 + 45 min + 10 min buffer = 09:55 > 09:50: the two groups exclude each other on the bay
    assertThat(created).hasSize(1);
    assertThat(created.get(0).jsonPath().getString("serviceBay.id"))
        .isEqualTo(Fixtures.HUDSON_BAY_1.toString());
    NoDoubleBookingAssert.verify(client);
  }
}
