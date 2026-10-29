package com.keyloop.scheduler.booking.block;

import static com.keyloop.scheduler.support.Bookings.LONDON;
import static com.keyloop.scheduler.support.Bookings.at;
import static com.keyloop.scheduler.support.Bookings.body;
import static com.keyloop.scheduler.support.Bookings.book;
import static com.keyloop.scheduler.support.Bookings.next;
import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.support.Dispatch;
import com.keyloop.scheduler.support.Fixtures;
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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Resource blocks end to end. */
class BlockApiIT extends IntegrationTest {

  private static final LocalDate WEDNESDAY = next(DayOfWeek.WEDNESDAY);
  private static final Instant WED_NINE = at(WEDNESDAY, 9, 0, LONDON);
  private static final Instant WED_TEN = at(WEDNESDAY, 10, 0, LONDON);
  private static final Instant WED_NOON = at(WEDNESDAY, 12, 0, LONDON);

  private JdbcClient client;

  @BeforeEach
  void configure() {
    RestAssured.port = port;
    client = JdbcClient.create(jdbc);
  }

  private static Response blockSoloBay(Instant start, Instant end, boolean overrideHold) {
    return Dispatch.block(
        Fixtures.SOLO, "BAY", Fixtures.SOLO_BAY, start, end, "Lift maintenance", overrideHold);
  }

  private static Response aliceBooks(Instant start) {
    return book(
        Fixtures.ALICE_SUB,
        body(Fixtures.SOLO, Fixtures.ALICE_FOCUS, start, Fixtures.OIL_CHANGE),
        "k-" + UUID.randomUUID());
  }

  @Test
  void blockPreventsBooking() {
    Response r = blockSoloBay(WED_NINE, WED_NOON, false);

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(201);
    String id = r.jsonPath().getString("id");
    assertThat(r.header("Location"))
        .endsWith("/api/v1/dealerships/" + Fixtures.SOLO + "/resource-blocks/" + id);
    assertThat(r.jsonPath().getString("resourceType")).isEqualTo("BAY");
    assertThat(r.jsonPath().getString("start")).isEqualTo(WED_NINE.toString());
    List<String> starts =
        Holds.starts(Fixtures.ALICE_SUB, Fixtures.SOLO, WEDNESDAY, Fixtures.OIL_CHANGE);
    assertThat(starts)
        .contains(at(WEDNESDAY, 8, 15, LONDON).toString(), WED_NOON.toString())
        .doesNotContain(
            at(WEDNESDAY, 8, 30, LONDON).toString(),
            WED_NINE.toString(),
            at(WEDNESDAY, 11, 15, LONDON).toString());
    Response booking = aliceBooks(WED_TEN);
    assertThat(booking.statusCode()).as(booking.asString()).isEqualTo(409);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'ResourceBlockCreated'"
                    + " AND aggregate_id = ?::uuid",
                Integer.class,
                id))
        .isEqualTo(1);
    NoDoubleBookingAssert.verify(client);
  }

  @Test
  void blockOverAppointment409ListsConflicts() {
    Response booked = aliceBooks(WED_NINE);
    String appointment = booked.jsonPath().getString("id");

    Response r =
        Dispatch.block(
            Fixtures.SOLO,
            "TECHNICIAN",
            Fixtures.SOLO_TECH,
            at(WEDNESDAY, 8, 0, LONDON),
            WED_TEN,
            "Training",
            false);

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(409);
    assertThat(r.jsonPath().getString("code")).isEqualTo("SLOT_UNAVAILABLE");
    assertThat(r.jsonPath().getList("conflicts", String.class)).containsExactly(appointment);
    assertThat(r.jsonPath().getString("detail"))
        .startsWith("Solo tech is not free between 08:00 on ")
        .endsWith("(Europe/London); move the conflicting appointments first.");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM resource_reservation WHERE kind = 'BLOCK'", Integer.class))
        .isZero();
  }

  @Test
  void removeBlockFreesTime() {
    String id = blockSoloBay(WED_NINE, WED_NOON, false).jsonPath().getString("id");

    Response removed = Dispatch.unblock(Fixtures.SOLO, id);

    assertThat(removed.statusCode()).isEqualTo(204);
    Response booking = aliceBooks(WED_TEN);
    assertThat(booking.statusCode()).as(booking.asString()).isEqualTo(201);
    List<Map<String, Object>> items =
        Dispatch.schedule(Fixtures.SOLO, WEDNESDAY, null).jsonPath().getList("items");
    assertThat(items).extracting(i -> i.get("kind")).containsExactly("APPOINTMENT");
    assertThat(
            jdbc.queryForObject(
                "SELECT aggregate_version FROM outbox_event WHERE event_type ="
                    + " 'ResourceBlockRemoved' AND aggregate_id = ?::uuid",
                Long.class,
                id))
        .isEqualTo(1L);
    Dispatch.unblock(Fixtures.SOLO, id).then().statusCode(404);
    NoDoubleBookingAssert.verify(client);
  }

  @Test
  void deleteUnknownBlock404() {
    Dispatch.unblock(Fixtures.SOLO, UUID.randomUUID().toString())
        .then()
        .statusCode(404)
        .contentType("application/problem+json");
    String id = blockSoloBay(WED_NINE, WED_NOON, false).jsonPath().getString("id");
    Dispatch.unblock(Fixtures.RIVERSIDE, id).then().statusCode(404);
  }

  @Test
  void blockOverHeldSlotIsSlotHeldUnlessOverridden() {
    Response hold =
        Holds.hold(
            Fixtures.BOB_SUB, Fixtures.SOLO, Fixtures.BOB_GOLF, WED_NINE, Fixtures.OIL_CHANGE);
    String holdId = hold.jsonPath().getString("id");

    Response refused = blockSoloBay(WED_NINE, WED_NOON, false);
    assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(409);
    assertThat(refused.jsonPath().getString("code")).isEqualTo("SLOT_HELD");

    Response forced = blockSoloBay(WED_NINE, WED_NOON, true);
    assertThat(forced.statusCode()).as(forced.asString()).isEqualTo(201);
    Response confirm =
        Holds.confirm(
            Fixtures.BOB_SUB,
            holdId,
            Fixtures.SOLO,
            Fixtures.BOB_GOLF,
            WED_NINE,
            Fixtures.OIL_CHANGE);
    assertThat(confirm.statusCode()).as(confirm.asString()).isEqualTo(409);
    NoDoubleBookingAssert.verify(client);
  }

  @Test
  void invalidBlocksAre400Or404() {
    Response noReason =
        Dispatch.block(Fixtures.SOLO, "BAY", Fixtures.SOLO_BAY, WED_NINE, WED_NOON, null, false);
    Response backwards = blockSoloBay(WED_NOON, WED_NINE, false);
    Response unknown =
        Dispatch.block(Fixtures.SOLO, "BAY", Fixtures.SOLO_TECH, WED_NINE, WED_NOON, "x", false);
    Response foreign =
        Dispatch.block(
            Fixtures.SOLO, "BAY", Fixtures.RIVERSIDE_BAY_1, WED_NINE, WED_NOON, "x", false);
    Response malformed =
        Dispatch.as(Fixtures.SOLO)
            .contentType(ContentType.JSON)
            .body("{\"start\": \"tomorrow\"}")
            .post(Dispatch.BLOCKS, Fixtures.SOLO);

    assertThat(noReason.statusCode()).isEqualTo(400);
    assertThat(noReason.jsonPath().getString("errors[0].field")).isEqualTo("reason");
    assertThat(backwards.statusCode()).isEqualTo(400);
    assertThat(unknown.statusCode()).isEqualTo(404);
    assertThat(foreign.statusCode()).isEqualTo(404);
    assertThat(malformed.statusCode()).isEqualTo(400);
  }
}
