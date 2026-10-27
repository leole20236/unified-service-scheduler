package com.keyloop.scheduler.availability;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Headers;
import com.keyloop.scheduler.support.IntegrationTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AvailabilityApiIT extends IntegrationTest {

  private static final String PATH = "/api/v1/dealerships/{id}/availability";

  @BeforeEach
  void configure() {
    RestAssured.port = port;
  }

  private static LocalDate futureDay(DayOfWeek day) {
    return LocalDate.now(ZoneOffset.UTC).plusDays(3).with(TemporalAdjusters.next(day));
  }

  private Response get(UUID dealership, String services, String from, String to) {
    return given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .queryParam("serviceTypeIds", services)
        .queryParam("from", from)
        .queryParam("to", to)
        .get(PATH, dealership);
  }

  @Test
  void returnsStartsInUtc() {
    // Hudson opens 07:00 New York time, never 07:00 UTC: the offset must be applied in any season
    LocalDate day = futureDay(DayOfWeek.TUESDAY);
    Instant opening = day.atTime(7, 0).atZone(ZoneId.of("America/New_York")).toInstant();

    Response response =
        get(Fixtures.HUDSON, Fixtures.OIL_CHANGE.toString(), day.toString(), day.toString());

    response
        .then()
        .statusCode(200)
        .header("Cache-Control", "no-store")
        .body("dealershipId", equalTo(Fixtures.HUDSON.toString()))
        .body("timeZone", equalTo("America/New_York"))
        .body("durationMinutes", equalTo(45))
        .body("days", hasSize(1))
        .body("days[0].date", equalTo(day.toString()));
    List<String> starts = response.jsonPath().getList("days[0].starts");
    assertThat(starts).isNotEmpty().allMatch(s -> s.endsWith("Z"));
    assertThat(starts.get(0)).isEqualTo(opening.toString()).isNotEqualTo(day + "T07:00:00Z");
  }

  @Test
  void startsBeyondSixtyDaysAreNotOffered() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate from = today.plusDays(61);

    get(Fixtures.SOLO, Fixtures.OIL_CHANGE.toString(), from.toString(), from.plusDays(2).toString())
        .then()
        .statusCode(200)
        .body("days", hasSize(3))
        .body("days[0].starts", empty())
        .body("days[1].starts", empty())
        .body("days[2].starts", empty());
    get(
            Fixtures.SOLO,
            Fixtures.OIL_CHANGE.toString(),
            today.plusDays(58).toString(),
            today.plusDays(58).toString())
        .then()
        .statusCode(200)
        .body("days[0].starts.size()", org.hamcrest.Matchers.greaterThan(0));
  }

  @Test
  void closedDayReturnsEmptyStarts() {
    LocalDate sunday = futureDay(DayOfWeek.SUNDAY);

    get(Fixtures.RIVERSIDE, Fixtures.OIL_CHANGE.toString(), sunday.toString(), sunday.toString())
        .then()
        .statusCode(200)
        .body("days[0].date", equalTo(sunday.toString()))
        .body("days[0].starts", empty());
  }

  @Test
  void reservationRemovesStartsUntilInactive() {
    LocalDate day = futureDay(DayOfWeek.WEDNESDAY);
    String from = day.toString();
    String oil = Fixtures.OIL_CHANGE.toString();
    // Solo, London: block both resources 09:00-10:00 local
    Instant blockStart = day.atTime(9, 0).atZone(ZoneId.of("Europe/London")).toInstant();
    String start = blockStart.toString();
    String end = blockStart.plusSeconds(3600).toString();
    for (String column : List.of("service_bay_id", "technician_id")) {
      UUID resource = column.equals("service_bay_id") ? Fixtures.SOLO_BAY : Fixtures.SOLO_TECH;
      jdbc.update(
          "INSERT INTO resource_reservation (id, dealership_id, "
              + column
              + ", kind, start_at, end_at) VALUES (?, ?, ?, 'BLOCK', ?::timestamptz,"
              + " ?::timestamptz)",
          UUID.randomUUID(),
          Fixtures.SOLO,
          resource,
          start,
          end);
    }

    List<String> whileBlocked = startsOf(get(Fixtures.SOLO, oil, from, from));
    assertThat(whileBlocked)
        .doesNotContain(start)
        .contains(blockStart.minusSeconds(3600).toString());

    jdbc.update("UPDATE resource_reservation SET active = false");

    assertThat(startsOf(get(Fixtures.SOLO, oil, from, from))).contains(start);
  }

  private static List<String> startsOf(Response r) {
    r.then().statusCode(200);
    return r.jsonPath().getList("days[0].starts");
  }

  @Test
  void rangeOverFourteenDaysIs400() {
    LocalDate from = futureDay(DayOfWeek.MONDAY);

    get(
            Fixtures.RIVERSIDE,
            Fixtures.OIL_CHANGE.toString(),
            from.toString(),
            from.plusDays(14).toString())
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_ERROR"));
  }

  @Test
  void fourteenDaysIsAccepted() {
    LocalDate from = futureDay(DayOfWeek.MONDAY);

    get(
            Fixtures.RIVERSIDE,
            Fixtures.OIL_CHANGE.toString(),
            from.toString(),
            from.plusDays(13).toString())
        .then()
        .statusCode(200)
        .body("days", hasSize(14));
  }

  @Test
  void fromAfterToIs400() {
    LocalDate from = futureDay(DayOfWeek.MONDAY);

    get(
            Fixtures.RIVERSIDE,
            Fixtures.OIL_CHANGE.toString(),
            from.toString(),
            from.minusDays(1).toString())
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_ERROR"));
  }

  @Test
  void badServiceIdIs400() {
    String day = futureDay(DayOfWeek.MONDAY).toString();
    UUID oil = Fixtures.OIL_CHANGE;

    for (String services :
        List.of("not-a-uuid", "", oil + "," + oil, oil + ",nope", "a,b,c,d,e,f")) {
      get(Fixtures.RIVERSIDE, services, day, day)
          .then()
          .statusCode(400)
          .body("code", equalTo("VALIDATION_ERROR"));
    }
    String six =
        String.join(
            ",",
            java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> UUID.randomUUID().toString())
                .toList());
    get(Fixtures.RIVERSIDE, six, day, day).then().statusCode(400);
    given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .queryParam("from", day)
        .queryParam("to", day)
        .get(PATH, Fixtures.RIVERSIDE)
        .then()
        .statusCode(400);
    get(Fixtures.RIVERSIDE, oil.toString(), "tomorrow", day).then().statusCode(400);
  }

  @Test
  void unknownDealershipIs404AndUnknownServiceIs422() {
    String day = futureDay(DayOfWeek.MONDAY).toString();

    get(UUID.randomUUID(), Fixtures.OIL_CHANGE.toString(), day, day)
        .then()
        .statusCode(404)
        .body("code", equalTo("NOT_FOUND"));
    get(Fixtures.RIVERSIDE, UUID.randomUUID().toString(), day, day)
        .then()
        .statusCode(422)
        .body("code", equalTo("SERVICE_NOT_OFFERED"));
  }

  @Test
  void dispatcherMaySeeEarlierStartsAndNeedsNoMembershipForCatalogReads() {
    String day = futureDay(DayOfWeek.MONDAY).toString();

    given()
        .headers(Headers.dispatcher(UUID.randomUUID(), Fixtures.HUDSON))
        .queryParam("serviceTypeIds", Fixtures.OIL_CHANGE.toString())
        .queryParam("from", day)
        .queryParam("to", day)
        .get(PATH, Fixtures.RIVERSIDE)
        .then()
        .statusCode(200);
  }

  @Test
  void unauthenticatedIs401() {
    given().get(PATH, Fixtures.RIVERSIDE).then().statusCode(401);
  }

  @Test
  void responseHasNoResourceNames() {
    String day = futureDay(DayOfWeek.MONDAY).toString();

    String body =
        get(Fixtures.RIVERSIDE, Fixtures.BRAKE_INSPECTION.toString(), day, day).asString();

    for (String name : List.of("Sam", "Lee", "Ana", "Tom", "Bay 1", "displayName")) {
      assertThat(body).doesNotContain(name);
    }
    assertThat(body).contains("starts");
  }
}
