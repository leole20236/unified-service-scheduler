package com.keyloop.scheduler.hold;

import static com.keyloop.scheduler.support.Bookings.LONDON;
import static com.keyloop.scheduler.support.Bookings.at;
import static com.keyloop.scheduler.support.Bookings.next;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.keyloop.scheduler.booking.ReservationRow;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.support.Bookings;
import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Holds;
import com.keyloop.scheduler.support.IntegrationTest;
import com.keyloop.scheduler.support.NoDoubleBookingAssert;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/** Redis losing data, drifting from the database, or going away never causes a double booking. */
class LedgerFailureIT extends IntegrationTest {

  private static final LocalDate WEDNESDAY = next(DayOfWeek.WEDNESDAY);
  private static final Instant NINE = at(WEDNESDAY, 9, 0, LONDON);

  @Autowired StringRedisTemplate redis;
  @Autowired SlotLedger ledger;

  @BeforeEach
  void configure() {
    RestAssured.port = port;
  }

  private int appointments() {
    return jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class);
  }

  private void flushAll() {
    redis.execute(
        (RedisCallback<Void>)
            c -> {
              c.serverCommands().flushAll();
              return null;
            });
  }

  private static Response aliceHolds() {
    return Holds.hold(
        Fixtures.ALICE_SUB, Fixtures.SOLO, Fixtures.ALICE_FOCUS, NINE, Fixtures.OIL_CHANGE);
  }

  private static Response aliceConfirms(String holdId) {
    return Holds.confirm(
        Fixtures.ALICE_SUB, holdId, Fixtures.SOLO, Fixtures.ALICE_FOCUS, NINE, Fixtures.OIL_CHANGE);
  }

  @Test
  void flushedLedgerStillPreventsDoubleBooking() {
    Response held = aliceHolds();
    held.then().statusCode(201);
    aliceConfirms(held.jsonPath().getString("id")).then().statusCode(201);
    flushAll();

    Holds.hold(Fixtures.BOB_SUB, Fixtures.SOLO, Fixtures.BOB_GOLF, NINE, Fixtures.OIL_CHANGE)
        .then()
        .statusCode(409)
        .body("code", equalTo("SLOT_UNAVAILABLE"));

    LedgerKeys keys = LedgerKeys.of(Fixtures.SOLO, WEDNESDAY);
    assertThat(redis.hasKey(keys.loaded())).isTrue();
    assertThat(redis.opsForZSet().range(keys.ledger(Fixtures.SOLO_TECH), 0, -1))
        .singleElement()
        .asString()
        .startsWith("R:");
    assertThat(appointments()).isEqualTo(1);
    NoDoubleBookingAssert.verify(JdbcClient.create(jdbc));
  }

  @Test
  void staleLedgerHealsOnConflict() {
    LedgerKeys keys = LedgerKeys.of(Fixtures.SOLO, WEDNESDAY);
    assertThat(Holds.starts(Fixtures.BOB_SUB, Fixtures.SOLO, WEDNESDAY, Fixtures.OIL_CHANGE))
        .contains(NINE.toString()); // ledger loaded
    UUID block = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO resource_reservation (id, dealership_id, technician_id, kind, start_at,"
            + " end_at, reason) VALUES (?, ?, ?, 'BLOCK', ?::timestamptz, ?::timestamptz,"
            + " 'behind the ledger''s back')",
        block,
        Fixtures.SOLO,
        Fixtures.SOLO_TECH,
        NINE.toString(),
        NINE.plusSeconds(3600).toString());
    // Tag the marker so we can tell a rebuilt marker from the stale one.
    redis.opsForValue().setIfPresent(keys.loaded(), "stale");

    Response held = aliceHolds();
    held.then().statusCode(201); // the ledger does not know about the block
    aliceConfirms(held.jsonPath().getString("id"))
        .then()
        .statusCode(409)
        .body("code", equalTo("SLOT_UNAVAILABLE"));

    assertThat(redis.opsForValue().get(keys.loaded()))
        .as("marker deleted by the conflict, then rebuilt from the primary")
        .isEqualTo("1");
    assertThat(redis.opsForZSet().range(keys.ledger(Fixtures.SOLO_TECH), 0, -1))
        .anySatisfy(m -> assertThat(m).startsWith("R:" + block + ":"));
    assertThat(Holds.starts(Fixtures.BOB_SUB, Fixtures.SOLO, WEDNESDAY, Fixtures.OIL_CHANGE))
        .doesNotContain(NINE.toString());
    assertThat(appointments()).isZero();
    NoDoubleBookingAssert.verify(JdbcClient.create(jdbc));
  }

  @Test
  void ledgerFollowsCommittedRemovalsAndAdditions() {
    Holds.starts(Fixtures.BOB_SUB, Fixtures.SOLO, WEDNESDAY, Fixtures.OIL_CHANGE);
    UUID reservation = UUID.randomUUID();
    TimeRange period = new TimeRange(NINE, NINE.plusSeconds(3600));

    ledger.applyCommitted(
        Fixtures.SOLO,
        WEDNESDAY,
        List.of(),
        Map.of(reservation, new ReservationRow(Fixtures.SOLO_BAY, ReservationRow.BAY, period)));
    assertThat(
            ledger
                .occupancy(Fixtures.SOLO, WEDNESDAY, false, null)
                .isFree(Fixtures.SOLO_BAY, period))
        .isFalse();

    ledger.applyCommitted(Fixtures.SOLO, WEDNESDAY, List.of(reservation), Map.of());
    LedgerKeys keys = LedgerKeys.of(Fixtures.SOLO, WEDNESDAY);
    assertThat(redis.opsForZSet().range(keys.ledger(Fixtures.SOLO_BAY), 0, -1)).isEmpty();
  }

  @Test
  void heldByNamesLiveHoldsAndOccupancyCanLeaveThemOut() {
    Response held = aliceHolds();
    held.then().statusCode(201);
    UUID holdId = UUID.fromString(held.jsonPath().getString("id"));
    TimeRange nine = new TimeRange(NINE, NINE.plusSeconds(600));

    assertThat(ledger.heldBy(Fixtures.SOLO, WEDNESDAY, Fixtures.SOLO_BAY, nine))
        .containsExactly(holdId);
    assertThat(
            ledger.heldBy(
                Fixtures.SOLO,
                WEDNESDAY,
                Fixtures.SOLO_BAY,
                new TimeRange(NINE.plusSeconds(3600), NINE.plusSeconds(7200))))
        .isEmpty();
    assertThat(
            ledger.occupancy(Fixtures.SOLO, WEDNESDAY, false, null).isFree(Fixtures.SOLO_BAY, nine))
        .isTrue();
    assertThat(
            ledger.occupancy(Fixtures.SOLO, WEDNESDAY, true, null).isFree(Fixtures.SOLO_BAY, nine))
        .isFalse();
  }

  /** A context of its own whose Redis is stopped mid-test. */
  @Nested
  class RedisDown {

    static final GenericContainer<?> OWN_REDIS =
        new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);

    static {
      OWN_REDIS.start();
    }

    @DynamicPropertySource
    static void ownRedis(DynamicPropertyRegistry registry) {
      registry.add("spring.data.redis.port", () -> OWN_REDIS.getMappedPort(6379));
    }

    @LocalServerPort int ownPort;

    @Test
    void redisDownHoldsReturn503AndDirectBookingWorks() {
      RestAssured.port = ownPort;
      aliceHolds().then().statusCode(201); // proves this context talks to its own Redis
      OWN_REDIS.stop();

      aliceHolds()
          .then()
          .statusCode(503)
          .header("Retry-After", "30")
          .body("code", equalTo("HOLDS_UNAVAILABLE"))
          .body("detail", equalTo("Holds are unavailable right now; you can still book directly."));
      Holds.get(Fixtures.ALICE_SUB, UUID.randomUUID().toString()).then().statusCode(503);
      assertThat(Holds.starts(Fixtures.BOB_SUB, Fixtures.SOLO, WEDNESDAY, Fixtures.OIL_CHANGE))
          .contains(NINE.toString()); // from the database, which never saw the lost hold
      Bookings.book(
              Fixtures.ALICE_SUB,
              Bookings.body(Fixtures.SOLO, Fixtures.ALICE_FOCUS, NINE, Fixtures.OIL_CHANGE),
              "direct-" + UUID.randomUUID())
          .then()
          .statusCode(201);
      Bookings.book(
              Fixtures.BOB_SUB,
              Bookings.body(Fixtures.SOLO, Fixtures.BOB_GOLF, NINE, Fixtures.OIL_CHANGE),
              "direct-" + UUID.randomUUID())
          .then()
          .statusCode(409)
          .body("code", equalTo("SLOT_UNAVAILABLE"));
      assertThat(appointments()).isEqualTo(1);
      NoDoubleBookingAssert.verify(JdbcClient.create(jdbc));
    }
  }
}
