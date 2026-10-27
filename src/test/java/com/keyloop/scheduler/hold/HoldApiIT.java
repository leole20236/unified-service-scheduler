package com.keyloop.scheduler.hold;

import static com.keyloop.scheduler.support.Bookings.LONDON;
import static com.keyloop.scheduler.support.Bookings.at;
import static com.keyloop.scheduler.support.Bookings.next;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Headers;
import com.keyloop.scheduler.support.Holds;
import com.keyloop.scheduler.support.IntegrationTest;
import com.keyloop.scheduler.support.NoDoubleBookingAssert;
import com.keyloop.scheduler.support.PrimaryConnectionCounter;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

/** {@code /me/holds} end to end, and confirming a hold on {@code /me/appointments}. */
@Import(PrimaryConnectionCounter.class)
class HoldApiIT extends IntegrationTest {

  private static final LocalDate WEDNESDAY = next(DayOfWeek.WEDNESDAY);
  private static final Instant NINE = at(WEDNESDAY, 9, 0, LONDON);
  private static final Instant ELEVEN = at(WEDNESDAY, 11, 0, LONDON);

  @Autowired StringRedisTemplate redis;

  @BeforeEach
  void configure() {
    RestAssured.port = port;
  }

  private static Response aliceHolds(UUID dealership, Instant start) {
    return Holds.hold(
        Fixtures.ALICE_SUB, dealership, Fixtures.ALICE_FOCUS, start, Fixtures.OIL_CHANGE);
  }

  private static Response bobHolds(UUID dealership, Instant start) {
    return Holds.hold(Fixtures.BOB_SUB, dealership, Fixtures.BOB_GOLF, start, Fixtures.OIL_CHANGE);
  }

  private static String id(Response created) {
    assertThat(created.statusCode()).as(created.asString()).isEqualTo(201);
    return created.jsonPath().getString("id");
  }

  private Set<String> members(UUID dealership, UUID resource) {
    return redis.opsForZSet().range(LedgerKeys.of(dealership, WEDNESDAY).ledger(resource), 0, -1);
  }

  @Test
  void holdThenConfirm() {
    Response held = aliceHolds(Fixtures.RIVERSIDE, NINE);

    held.then()
        .statusCode(201)
        .header("Location", "/api/v1/me/holds/" + held.jsonPath().getString("id"))
        .header("Cache-Control", "no-store")
        .body("dealershipId", equalTo(Fixtures.RIVERSIDE.toString()))
        .body("serviceTypeIds", equalTo(java.util.List.of(Fixtures.OIL_CHANGE.toString())))
        .body("start", equalTo(NINE.toString()))
        .body("end", equalTo(NINE.plusSeconds(45 * 60).toString()));
    String holdId = id(held);
    Instant expiresAt = Instant.parse(held.jsonPath().getString("expiresAt"));
    assertThat(expiresAt).isBetween(Instant.now().plusSeconds(500), Instant.now().plusSeconds(601));
    String holdKey = LedgerKeys.of(Fixtures.RIVERSIDE, WEDNESDAY).hold(UUID.fromString(holdId));
    UUID bay = UUID.fromString((String) redis.opsForHash().get(holdKey, "bayId"));
    UUID technician = UUID.fromString((String) redis.opsForHash().get(holdKey, "technicianId"));

    Response booked =
        Holds.confirm(
            Fixtures.ALICE_SUB,
            holdId,
            Fixtures.RIVERSIDE,
            Fixtures.ALICE_FOCUS,
            NINE,
            Fixtures.OIL_CHANGE);

    booked.then().statusCode(201);
    assertThat(booked.jsonPath().getString("serviceBay.id")).isEqualTo(bay.toString());
    assertThat(booked.jsonPath().getString("technician.id")).isEqualTo(technician.toString());
    for (UUID resource : java.util.List.of(bay, technician)) {
      assertThat(members(Fixtures.RIVERSIDE, resource))
          .singleElement()
          .satisfies(m -> assertThat(m).startsWith("R:"));
    }
    assertThat(redis.hasKey(holdKey)).isFalse();
    assertThat(redis.hasKey(LedgerKeys.holdRef(UUID.fromString(holdId)))).isFalse();
    assertThat(
            redis.hasKey(LedgerKeys.of(Fixtures.RIVERSIDE, WEDNESDAY).userHold(Fixtures.ALICE_SUB)))
        .isFalse();
    NoDoubleBookingAssert.verify(JdbcClient.create(jdbc));
  }

  @Test
  void heldSlotHiddenFromOthersAvailability() {
    id(aliceHolds(Fixtures.SOLO, NINE));

    assertThat(Holds.starts(Fixtures.BOB_SUB, Fixtures.SOLO, WEDNESDAY, Fixtures.OIL_CHANGE))
        .doesNotContain(NINE.toString(), NINE.minusSeconds(30 * 60).toString())
        .contains(NINE.minusSeconds(45 * 60).toString(), NINE.plusSeconds(45 * 60).toString());
    assertThat(Holds.starts(Fixtures.ALICE_SUB, Fixtures.SOLO, WEDNESDAY, Fixtures.OIL_CHANGE))
        .contains(NINE.toString());
  }

  @Test
  void secondHoldReplacesFirst() {
    String first = id(aliceHolds(Fixtures.SOLO, NINE));
    id(aliceHolds(Fixtures.SOLO, ELEVEN));

    Holds.get(Fixtures.ALICE_SUB, first).then().statusCode(404);
    assertThat(Holds.starts(Fixtures.BOB_SUB, Fixtures.SOLO, WEDNESDAY, Fixtures.OIL_CHANGE))
        .contains(NINE.toString())
        .doesNotContain(ELEVEN.toString());
    id(bobHolds(Fixtures.SOLO, NINE));
  }

  @Test
  void othersHoldIdIs404() {
    String alices = id(aliceHolds(Fixtures.SOLO, NINE));

    Holds.get(Fixtures.BOB_SUB, alices)
        .then()
        .statusCode(404)
        .body("code", equalTo("NOT_FOUND"))
        .body("detail", equalTo("Hold not found."));
    Holds.get(Fixtures.ALICE_SUB, UUID.randomUUID().toString()).then().statusCode(404);
  }

  @Test
  void deleteIsIdempotent() {
    String alices = id(aliceHolds(Fixtures.SOLO, NINE));
    String bobs = id(bobHolds(Fixtures.SOLO, ELEVEN));

    Holds.delete(Fixtures.ALICE_SUB, alices).then().statusCode(204);
    Holds.delete(Fixtures.ALICE_SUB, alices).then().statusCode(204);
    Holds.get(Fixtures.ALICE_SUB, alices).then().statusCode(404);
    assertThat(Holds.starts(Fixtures.BOB_SUB, Fixtures.SOLO, WEDNESDAY, Fixtures.OIL_CHANGE))
        .contains(NINE.toString());

    Holds.delete(Fixtures.ALICE_SUB, bobs).then().statusCode(204);
    Holds.get(Fixtures.BOB_SUB, bobs).then().statusCode(200);
    assertThat(Holds.starts(Fixtures.ALICE_SUB, Fixtures.SOLO, WEDNESDAY, Fixtures.OIL_CHANGE))
        .doesNotContain(ELEVEN.toString());
  }

  @Test
  void getHoldRestoresCountdown() {
    Response held = aliceHolds(Fixtures.SOLO, NINE);
    String holdId = id(held);

    Holds.get(Fixtures.ALICE_SUB, holdId)
        .then()
        .statusCode(200)
        .header("Cache-Control", "no-store")
        .body("id", equalTo(holdId))
        .body("dealershipId", equalTo(Fixtures.SOLO.toString()))
        .body("serviceTypeIds", hasItem(Fixtures.OIL_CHANGE.toString()))
        .body("start", equalTo(NINE.toString()))
        .body("end", equalTo(NINE.plusSeconds(45 * 60).toString()))
        .body("expiresAt", equalTo(held.jsonPath().getString("expiresAt")));
  }

  @Test
  void holdRejectionTouchesNoDatabase() {
    id(aliceHolds(Fixtures.SOLO, NINE)); // loads the ledger and warms the catalog cache

    int before = PrimaryConnectionCounter.BORROWED.get();
    Response rejected = bobHolds(Fixtures.SOLO, NINE);
    int borrowed = PrimaryConnectionCounter.BORROWED.get() - before;

    rejected
        .then()
        .statusCode(409)
        .body("code", equalTo("SLOT_UNAVAILABLE"))
        .body("alternatives", hasSize(5))
        .body("alternatives[0]", equalTo(NINE.plusSeconds(45 * 60).toString()));
    assertThat(borrowed).as("primary connections borrowed by a rejected hold").isZero();
  }

  @Test
  void validationAndRoleErrors() {
    given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .contentType(ContentType.JSON)
        .body(Map.of("dealershipId", Fixtures.SOLO.toString()))
        .post(Holds.ME)
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_ERROR"));
    given()
        .headers(Headers.dispatcher(UUID.randomUUID(), Fixtures.SOLO))
        .contentType(ContentType.JSON)
        .body(Holds.body(Fixtures.SOLO, Fixtures.ALICE_FOCUS, NINE, Fixtures.OIL_CHANGE))
        .post(Holds.ME)
        .then()
        .statusCode(403);
    Holds.hold(Fixtures.BOB_SUB, Fixtures.SOLO, Fixtures.ALICE_FOCUS, NINE, Fixtures.OIL_CHANGE)
        .then()
        .statusCode(404);
    aliceHolds(Fixtures.SOLO, Instant.now().plusSeconds(600))
        .then()
        .statusCode(422)
        .body("code", equalTo("OUTSIDE_BOOKING_WINDOW"));
  }

  @Test
  void confirmingWithAnotherStartIsHoldMismatch() {
    String holdId = id(aliceHolds(Fixtures.SOLO, NINE));

    Holds.confirm(
            Fixtures.ALICE_SUB,
            holdId,
            Fixtures.SOLO,
            Fixtures.ALICE_FOCUS,
            ELEVEN,
            Fixtures.OIL_CHANGE)
        .then()
        .statusCode(422)
        .body("code", equalTo("HOLD_MISMATCH"));
    Holds.confirm(
            Fixtures.BOB_SUB, holdId, Fixtures.SOLO, Fixtures.BOB_GOLF, NINE, Fixtures.OIL_CHANGE)
        .then()
        .statusCode(404);
  }

  @Test
  void directBookingIsWrittenThroughToTheLedger() {
    Holds.starts(Fixtures.BOB_SUB, Fixtures.SOLO, WEDNESDAY, Fixtures.OIL_CHANGE); // loads it

    Holds.confirm(
            Fixtures.ALICE_SUB,
            UUID.randomUUID().toString(),
            Fixtures.SOLO,
            Fixtures.ALICE_FOCUS,
            NINE,
            Fixtures.OIL_CHANGE)
        .then()
        .statusCode(201);

    assertThat(members(Fixtures.SOLO, Fixtures.SOLO_BAY))
        .singleElement()
        .asString()
        .startsWith("R:");
    bobHolds(Fixtures.SOLO, NINE).then().statusCode(409);
  }

  /** Holds that lapse after one second. */
  @Nested
  @TestPropertySource(properties = "app.hold.ttl=1s")
  class ShortHolds {

    @LocalServerPort int shortPort;

    @Test
    void expiredHoldStillBooksIfFree() throws InterruptedException {
      RestAssured.port = shortPort;
      String holdId = id(aliceHolds(Fixtures.SOLO, NINE));

      Thread.sleep(1_500);

      Holds.get(Fixtures.ALICE_SUB, holdId).then().statusCode(404);
      id(bobHolds(Fixtures.SOLO, ELEVEN)); // the expired hold no longer blocks anything
      Response booked =
          Holds.confirm(
              Fixtures.ALICE_SUB,
              holdId,
              Fixtures.SOLO,
              Fixtures.ALICE_FOCUS,
              NINE,
              Fixtures.OIL_CHANGE);

      booked.then().statusCode(201).body("start", equalTo(NINE.toString()));
      NoDoubleBookingAssert.verify(JdbcClient.create(jdbc));
    }

    @Test
    void expiredHoldDoesNotBlockOthers() throws InterruptedException {
      RestAssured.port = shortPort;
      id(aliceHolds(Fixtures.SOLO, NINE));

      Thread.sleep(1_500);

      id(bobHolds(Fixtures.SOLO, NINE));
    }
  }
}
