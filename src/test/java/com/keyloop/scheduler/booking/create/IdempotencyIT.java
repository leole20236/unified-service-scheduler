package com.keyloop.scheduler.booking.create;

import static com.keyloop.scheduler.support.Bookings.LONDON;
import static com.keyloop.scheduler.support.Bookings.at;
import static com.keyloop.scheduler.support.Bookings.body;
import static com.keyloop.scheduler.support.Bookings.book;
import static com.keyloop.scheduler.support.Bookings.next;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.keyloop.scheduler.support.Bookings;
import com.keyloop.scheduler.support.ConcurrencyHarness;
import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.IntegrationTest;
import com.keyloop.scheduler.support.NoDoubleBookingAssert;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class IdempotencyIT extends IntegrationTest {

  @BeforeEach
  void configure() {
    RestAssured.port = port;
  }

  private int appointments() {
    return jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class);
  }

  private static Map<String, Object> aliceAt(int hour) {
    Instant start = at(next(DayOfWeek.MONDAY), hour, 0, LONDON);
    return body(Fixtures.RIVERSIDE, Fixtures.ALICE_FOCUS, start, Fixtures.OIL_CHANGE);
  }

  @Test
  void retryReturnsSameAppointment() {
    Map<String, Object> body = aliceAt(9);

    Response first = book(Fixtures.ALICE_SUB, body, "retry-1");
    Response second = book(Fixtures.ALICE_SUB, body, "retry-1");

    first.then().statusCode(201);
    assertThat(first.header("Idempotency-Replayed")).isNull();
    second
        .then()
        .statusCode(201)
        .header("Idempotency-Replayed", "true")
        .header("ETag", "\"0\"")
        .header("Location", first.header("Location"))
        .body("id", equalTo(first.jsonPath().getString("id")));
    assertThat(second.jsonPath().getMap("$")).isEqualTo(first.jsonPath().getMap("$"));
    assertThat(appointments()).isEqualTo(1);
    NoDoubleBookingAssert.verify(JdbcClient.create(jdbc));
  }

  @Test
  void sameKeyDifferentBody422() {
    book(Fixtures.ALICE_SUB, aliceAt(9), "reuse-1").then().statusCode(201);

    book(Fixtures.ALICE_SUB, aliceAt(11), "reuse-1")
        .then()
        .statusCode(422)
        .body("code", equalTo("IDEMPOTENCY_KEY_REUSED"));
    assertThat(appointments()).isEqualTo(1);
  }

  @Test
  void sameKeyOnTheOtherEndpointIsReuse() {
    UUID both = Fixtures.ALICE_SUB;
    Map<String, Object> body = aliceAt(9);
    book(both, body, "route-1").then().statusCode(201);
    Map<String, Object> dispatcher = new HashMap<>(body);
    dispatcher.remove("dealershipId");
    dispatcher.put("customerId", Fixtures.ALICE.toString());

    given()
        .header("X-User-Id", both.toString())
        .header("X-User-Roles", "CUSTOMER,DISPATCHER")
        .header("X-Dealership-Ids", Fixtures.RIVERSIDE.toString())
        .header("Idempotency-Key", "route-1")
        .contentType(ContentType.JSON)
        .body(dispatcher)
        .post(Bookings.DISPATCH, Fixtures.RIVERSIDE)
        .then()
        .statusCode(422)
        .body("code", equalTo("IDEMPOTENCY_KEY_REUSED"));
  }

  @Test
  void keysAreScopedPerUser() {
    Bookings.Customer other = Bookings.newCustomer(jdbc);
    book(Fixtures.ALICE_SUB, aliceAt(9), "shared").then().statusCode(201);

    Instant start = at(next(DayOfWeek.MONDAY), 13, 0, LONDON);
    book(other, body(Fixtures.RIVERSIDE, other.vehicleId(), start, Fixtures.OIL_CHANGE), "shared")
        .then()
        .statusCode(201);
    assertThat(appointments()).isEqualTo(2);
  }

  @Test
  void failedBookingLeavesTheKeyReusable() {
    Map<String, Object> bobsCar =
        body(
            Fixtures.RIVERSIDE,
            Fixtures.BOB_GOLF,
            at(next(DayOfWeek.MONDAY), 9, 0, LONDON),
            Fixtures.OIL_CHANGE);
    book(Fixtures.ALICE_SUB, bobsCar, "fresh").then().statusCode(404);

    book(Fixtures.ALICE_SUB, aliceAt(9), "fresh").then().statusCode(201);
  }

  @Tag("concurrency")
  @Test
  void parallelDuplicatesCreateOneAppointment() {
    Map<String, Object> body = aliceAt(10);

    List<Response> responses =
        ConcurrencyHarness.race(10, i -> book(Fixtures.ALICE_SUB, body, "parallel-1"));

    List<Response> created = responses.stream().filter(r -> r.statusCode() == 201).toList();
    assertThat(created).isNotEmpty();
    assertThat(created.stream().map(r -> r.jsonPath().getString("id")).distinct()).hasSize(1);
    assertThat(created.stream().filter(r -> r.header("Idempotency-Replayed") == null)).hasSize(1);
    for (Response r : responses) {
      if (r.statusCode() != 201) {
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(r.jsonPath().getString("code")).isEqualTo("REQUEST_IN_PROGRESS");
        assertThat(r.header("Retry-After")).isEqualTo("1");
      }
    }
    assertThat(appointments()).isEqualTo(1);
    NoDoubleBookingAssert.verify(JdbcClient.create(jdbc));
  }
}
