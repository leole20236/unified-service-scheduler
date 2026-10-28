package com.keyloop.scheduler.booking;

import static com.keyloop.scheduler.support.Bookings.LONDON;
import static com.keyloop.scheduler.support.Bookings.at;
import static com.keyloop.scheduler.support.Bookings.next;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.SchedulerApplication;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.support.Bookings;
import com.keyloop.scheduler.support.Bookings.Customer;
import com.keyloop.scheduler.support.ConcurrencyHarness;
import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Headers;
import com.keyloop.scheduler.support.Holds;
import com.keyloop.scheduler.support.IntegrationTest;
import com.keyloop.scheduler.support.NoDoubleBookingAssert;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The zero-double-booking guarantee across servers: a second application instance runs against the
 * same PostgreSQL and Redis, and 100 requests for one Solo slot are split between the two (half
 * through holds, half direct). Nothing in either JVM coordinates them; only the database does.
 */
@Tag("concurrency")
class MultiInstanceConcurrencyIT extends IntegrationTest {

  private static final LocalDate WEDNESDAY = next(DayOfWeek.WEDNESDAY);
  private static final Instant WED_NINE = at(WEDNESDAY, 9, 0, LONDON);
  private static final int REQUESTS = 100;

  private static ConfigurableApplicationContext second;

  @BeforeAll
  static void startSecondInstance() {
    List<String> properties = new ArrayList<>(List.of(sharedInfrastructureProperties()));
    properties.add("server.port=0");
    properties.add("app.pool.primary-size=10");
    properties.add("app.pool.replica-size=2");
    properties.add("spring.main.banner-mode=off");
    // As command-line arguments: they outrank application.yml (builder defaults would not).
    second =
        new SpringApplicationBuilder(SchedulerApplication.class)
            .run(properties.stream().map(p -> "--" + p).toArray(String[]::new));
  }

  @AfterAll
  static void stopSecondInstance() {
    if (second != null) {
      second.close();
    }
  }

  private static Response holdAndConfirm(int port, Customer c) {
    Response hold =
        given()
            .port(port)
            .headers(Headers.customer(c.subject()))
            .contentType(ContentType.JSON)
            .body(Holds.body(Fixtures.SOLO, c.vehicleId(), WED_NINE, Fixtures.OIL_CHANGE))
            .post(Holds.ME);
    if (hold.statusCode() != 201) {
      return hold;
    }
    Map<String, Object> body =
        Bookings.body(Fixtures.SOLO, c.vehicleId(), WED_NINE, Fixtures.OIL_CHANGE);
    body.put("holdId", hold.jsonPath().getString("id"));
    return book(port, c, body);
  }

  private static Response book(int port, Customer c, Map<String, Object> body) {
    return given()
        .port(port)
        .headers(Headers.customer(c.subject()))
        .header("Idempotency-Key", "multi-" + UUID.randomUUID())
        .contentType(ContentType.JSON)
        .body(body)
        .post(Bookings.ME);
  }

  @RepeatedTest(5)
  void oneSlotTwoInstancesExactlyOneBooking() {
    int secondPort = ((WebServerApplicationContext) second).getWebServer().getPort();
    assertThat(secondPort).isNotEqualTo(port);
    List<Customer> customers = Bookings.newCustomers(jdbc, REQUESTS);

    List<Response> responses =
        ConcurrencyHarness.race(
            REQUESTS,
            i -> {
              int target = i % 2 == 0 ? port : secondPort;
              Customer c = customers.get(i);
              return (i / 2) % 2 == 0
                  ? holdAndConfirm(target, c)
                  : book(
                      target,
                      c,
                      Bookings.body(Fixtures.SOLO, c.vehicleId(), WED_NINE, Fixtures.OIL_CHANGE));
            });

    assertThat(responses).hasSize(REQUESTS);
    assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(1);
    for (Response r : responses) {
      if (r.statusCode() != 201) {
        assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
        assertThat(r.jsonPath().getString("code")).isEqualTo("SLOT_UNAVAILABLE");
      }
    }
    JdbcClient client = JdbcClient.create(jdbc);
    TimeRange slot = new TimeRange(WED_NINE, WED_NINE.plus(Duration.ofMinutes(45)));
    assertThat(NoDoubleBookingAssert.countActive(client, Fixtures.SOLO_BAY, slot)).isEqualTo(1);
    assertThat(NoDoubleBookingAssert.countActive(client, Fixtures.SOLO_TECH, slot)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class)).isEqualTo(1);
    NoDoubleBookingAssert.verify(client);
  }
}
