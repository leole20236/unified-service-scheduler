package com.keyloop.scheduler.query;

import static com.keyloop.scheduler.support.Bookings.LONDON;
import static com.keyloop.scheduler.support.Bookings.at;
import static com.keyloop.scheduler.support.Bookings.body;
import static com.keyloop.scheduler.support.Bookings.book;
import static com.keyloop.scheduler.support.Bookings.next;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Headers;
import com.keyloop.scheduler.support.IntegrationTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code GET /me/appointments} and {@code GET /me/appointments/{id}} end to end. */
class MyAppointmentsApiIT extends IntegrationTest {

  private static final String ME = "/api/v1/me/appointments";
  private static final LocalDate WEDNESDAY = next(DayOfWeek.WEDNESDAY);

  @BeforeEach
  void configure() {
    RestAssured.port = port;
  }

  private static String aliceBooks(UUID dealership, Instant start) {
    Response r =
        book(
            Fixtures.ALICE_SUB,
            body(dealership, Fixtures.ALICE_FOCUS, start, Fixtures.OIL_CHANGE),
            "k-" + UUID.randomUUID());
    assertThat(r.statusCode()).as(r.asString()).isEqualTo(201);
    return r.jsonPath().getString("id");
  }

  private static Response listAs(UUID subject, String query) {
    return given().headers(Headers.customer(subject)).get(ME + query);
  }

  @Test
  void listShowsOnlyMine() {
    String mine = aliceBooks(Fixtures.SOLO, at(WEDNESDAY, 9, 0, LONDON));
    Response bob =
        book(
            Fixtures.BOB_SUB,
            body(
                Fixtures.SOLO,
                Fixtures.BOB_GOLF,
                at(WEDNESDAY, 11, 0, LONDON),
                Fixtures.OIL_CHANGE),
            "bob");
    assertThat(bob.statusCode()).isEqualTo(201);

    listAs(Fixtures.ALICE_SUB, "")
        .then()
        .statusCode(200)
        .body("items", hasSize(1))
        .body("items[0].id", equalTo(mine))
        .body("items[0].services[0].code", equalTo("OIL_CHANGE"))
        .body("nextCursor", nullValue());
  }

  @Test
  void getReturnsEtagAndOthersIs404() {
    String id = aliceBooks(Fixtures.SOLO, at(WEDNESDAY, 9, 0, LONDON));

    given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .get(ME + "/" + id)
        .then()
        .statusCode(200)
        .header("ETag", "\"0\"")
        .body("id", equalTo(id));
    given()
        .headers(Headers.customer(Fixtures.BOB_SUB))
        .get(ME + "/" + id)
        .then()
        .statusCode(404)
        .body("code", equalTo("NOT_FOUND"));
    given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .get(ME + "/" + UUID.randomUUID())
        .then()
        .statusCode(404);
  }

  @Test
  void justBookedVisibleImmediately() {
    String id = aliceBooks(Fixtures.SOLO, at(WEDNESDAY, 9, 0, LONDON));

    listAs(Fixtures.ALICE_SUB, "").then().body("items.id", org.hamcrest.Matchers.hasItem(id));
  }

  @Test
  void paginationCursorWorks() {
    List<String> booked = new ArrayList<>();
    for (int day = 0; day < 3; day++) {
      for (int hour = 8; hour <= 16 && booked.size() < 25; hour++) {
        booked.add(aliceBooks(Fixtures.RIVERSIDE, at(WEDNESDAY.plusDays(day), hour, 0, LONDON)));
      }
    }
    assertThat(booked).hasSize(25);

    Set<String> seen = new HashSet<>();
    List<Integer> sizes = new ArrayList<>();
    List<String> starts = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      Response page =
          listAs(Fixtures.ALICE_SUB, "?limit=10" + (cursor == null ? "" : "&cursor=" + cursor));
      page.then().statusCode(200);
      List<String> ids = page.jsonPath().getList("items.id");
      sizes.add(ids.size());
      starts.addAll(page.jsonPath().getList("items.start"));
      for (String id : ids) {
        assertThat(seen.add(id)).as("duplicate " + id).isTrue();
      }
      cursor = page.jsonPath().getString("nextCursor");
      pages++;
    } while (cursor != null && pages < 10);

    assertThat(sizes).containsExactly(10, 10, 5);
    assertThat(seen).containsExactlyInAnyOrderElementsOf(booked);
    assertThat(starts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
  }

  @Test
  void filtersByStatusAndRange() {
    aliceBooks(Fixtures.SOLO, at(WEDNESDAY, 9, 0, LONDON));
    String later = aliceBooks(Fixtures.SOLO, at(WEDNESDAY.plusDays(1), 9, 0, LONDON));

    listAs(Fixtures.ALICE_SUB, "?status=CANCELLED").then().body("items", hasSize(0));
    listAs(Fixtures.ALICE_SUB, "?status=BOOKED").then().body("items", hasSize(2));
    listAs(Fixtures.ALICE_SUB, "?from=" + at(WEDNESDAY.plusDays(1), 0, 0, LONDON))
        .then()
        .body("items", hasSize(1))
        .body("items[0].id", equalTo(later));
    listAs(Fixtures.ALICE_SUB, "?to=" + at(WEDNESDAY.plusDays(1), 0, 0, LONDON))
        .then()
        .body("items", hasSize(1));
  }

  @Test
  void limitOver100Is400() {
    listAs(Fixtures.ALICE_SUB, "?limit=101")
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_ERROR"));
    listAs(Fixtures.ALICE_SUB, "?limit=0").then().statusCode(400);
  }

  @Test
  void badParametersAre400() {
    listAs(Fixtures.ALICE_SUB, "?cursor=garbage!!")
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_ERROR"));
    listAs(Fixtures.ALICE_SUB, "?status=NOPE").then().statusCode(400);
    listAs(Fixtures.ALICE_SUB, "?from=yesterday").then().statusCode(400);
    listAs(Fixtures.ALICE_SUB, "?to=tomorrow").then().statusCode(400);
  }

  @Test
  void dispatcherIsForbidden() {
    given()
        .headers(Headers.dispatcher(UUID.randomUUID(), Fixtures.SOLO))
        .get(ME)
        .then()
        .statusCode(403);
  }
}
