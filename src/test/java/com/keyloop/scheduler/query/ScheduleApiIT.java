package com.keyloop.scheduler.query;

import static com.keyloop.scheduler.support.Bookings.LONDON;
import static com.keyloop.scheduler.support.Bookings.at;
import static com.keyloop.scheduler.support.Bookings.next;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.support.Dispatch;
import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Headers;
import com.keyloop.scheduler.support.IntegrationTest;
import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The dispatcher's Gantt board end to end. */
class ScheduleApiIT extends IntegrationTest {

  private static final LocalDate WEDNESDAY = next(DayOfWeek.WEDNESDAY);
  private static final Instant WED_NINE = at(WEDNESDAY, 9, 0, LONDON);

  @BeforeEach
  void configure() {
    RestAssured.port = port;
  }

  private static Map<String, Object> item(JsonPath json, String kind) {
    List<Map<String, Object>> items = json.getList("items");
    return items.stream().filter(i -> kind.equals(i.get("kind"))).findFirst().orElseThrow();
  }

  @Test
  void scheduleListsAppointmentsAndBlocks() {
    final String id =
        Dispatch.booked(
            Fixtures.RIVERSIDE,
            Fixtures.ALICE,
            Fixtures.ALICE_FOCUS,
            WED_NINE,
            Fixtures.OIL_CHANGE);
    Response block =
        Dispatch.block(
            Fixtures.RIVERSIDE,
            "BAY",
            Fixtures.RIVERSIDE_BAY_3,
            at(WEDNESDAY, 14, 0, LONDON),
            at(WEDNESDAY, 16, 0, LONDON),
            "Lift maintenance",
            false);
    assertThat(block.statusCode()).as(block.asString()).isEqualTo(201);
    // Not on the board: another day, and a cancelled job.
    Dispatch.booked(
        Fixtures.RIVERSIDE,
        Fixtures.BOB,
        Fixtures.BOB_GOLF,
        WED_NINE.plus(Duration.ofDays(1)),
        Fixtures.OIL_CHANGE);
    String cancelled =
        Dispatch.booked(
            Fixtures.RIVERSIDE,
            Fixtures.BOB,
            Fixtures.BOB_GOLF,
            at(WEDNESDAY, 12, 0, LONDON),
            Fixtures.OIL_CHANGE);
    Dispatch.acted(Fixtures.RIVERSIDE, cancelled, "cancel", null);

    Response r = Dispatch.schedule(Fixtures.RIVERSIDE, WEDNESDAY, null);

    assertThat(r.statusCode()).as(r.asString()).isEqualTo(200);
    assertThat(r.header("Cache-Control")).contains("no-store");
    JsonPath json = r.jsonPath();
    assertThat(json.getString("date")).isEqualTo(WEDNESDAY.toString());
    assertThat(json.getString("timeZone")).isEqualTo("Europe/London");
    assertThat(json.getString("groupBy")).isEqualTo("BAY");
    assertThat(json.getString("openingHours.opens")).isEqualTo("08:00");
    assertThat(json.getString("openingHours.closes")).isEqualTo("18:00");
    assertThat(json.getList("lanes.id", String.class))
        .containsExactly(
            Fixtures.RIVERSIDE_BAY_1.toString(),
            Fixtures.RIVERSIDE_BAY_2.toString(),
            Fixtures.RIVERSIDE_BAY_3.toString());
    assertThat(json.getList("lanes.type", String.class)).containsOnly("BAY");
    assertThat(json.getList("technicians.displayName", String.class))
        .containsExactly("Ana", "Lee", "Sam", "Tom");
    assertThat(json.getList("technicians.find { it.displayName == 'Sam' }.skills", String.class))
        .containsExactly("BRAKES", "OIL");
    assertThat(json.getList("items")).hasSize(2);
    Map<String, Object> appointment = item(json, "APPOINTMENT");
    Map<String, Object> stored =
        jdbc.queryForMap(
            "SELECT service_bay_id::text AS bay, technician_id::text AS tech FROM appointment"
                + " WHERE id = ?::uuid",
            id);
    assertThat(appointment)
        .containsEntry("id", id)
        .containsEntry("version", 0)
        .containsEntry("status", "BOOKED")
        .containsEntry("laneId", stored.get("bay"))
        .containsEntry("serviceBayId", stored.get("bay"))
        .containsEntry("technicianId", stored.get("tech"))
        .containsEntry("start", WED_NINE.toString())
        .containsEntry("end", WED_NINE.plus(Duration.ofMinutes(45)).toString())
        .containsEntry("vehicle", "ALI 1 · Ford Focus")
        .containsEntry("services", List.of("OIL_CHANGE"))
        .containsEntry("overrun", false)
        .containsEntry("holdReason", null)
        .containsEntry("remainingMinutes", null);
    assertThat(item(json, "BLOCK"))
        .containsEntry("id", block.jsonPath().getString("id"))
        .containsEntry("laneId", Fixtures.RIVERSIDE_BAY_3.toString())
        .containsEntry("resourceType", "BAY")
        .containsEntry("reason", "Lift maintenance")
        .containsEntry("start", at(WEDNESDAY, 14, 0, LONDON).toString())
        .containsEntry("end", at(WEDNESDAY, 16, 0, LONDON).toString());
  }

  @Test
  void onHoldAndOverrunningJobsAreMarked() {
    String overrunning =
        Dispatch.booked(
            Fixtures.RIVERSIDE,
            Fixtures.ALICE,
            Fixtures.ALICE_FOCUS,
            WED_NINE,
            Fixtures.OIL_CHANGE);
    String onHold =
        Dispatch.booked(
            Fixtures.RIVERSIDE,
            Fixtures.BOB,
            Fixtures.BOB_GOLF,
            WED_NINE,
            Fixtures.BRAKE_INSPECTION);
    Instant twoHoursAgo = Dispatch.dbNow(jdbc).minus(Duration.ofHours(2));
    Dispatch.shift(jdbc, overrunning, twoHoursAgo);
    Dispatch.shift(jdbc, onHold, twoHoursAgo);
    jdbc.update(
        "UPDATE appointment SET status = 'IN_PROGRESS', started_at = start_at WHERE id = ?::uuid",
        overrunning);
    jdbc.update(
        "UPDATE appointment SET status = 'ON_HOLD', hold_reason = 'PARTS', worked_minutes = 30"
            + " WHERE id = ?::uuid",
        onHold);

    JsonPath json =
        Dispatch.schedule(Fixtures.RIVERSIDE, twoHoursAgo.atZone(LONDON).toLocalDate(), "BAY")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    List<Map<String, Object>> items = json.getList("items");
    Map<String, Object> late =
        items.stream().filter(i -> overrunning.equals(i.get("id"))).findFirst().orElseThrow();
    Map<String, Object> held =
        items.stream().filter(i -> onHold.equals(i.get("id"))).findFirst().orElseThrow();
    assertThat(late).containsEntry("status", "IN_PROGRESS").containsEntry("overrun", true);
    assertThat(held)
        .containsEntry("status", "ON_HOLD")
        .containsEntry("holdReason", "PARTS")
        .containsEntry("remainingMinutes", 45)
        .containsEntry("technicianId", null)
        .containsEntry("overrun", false);
  }

  @Test
  void groupByTechnicianUsesTechnicianLanes() {
    String id =
        Dispatch.booked(
            Fixtures.RIVERSIDE,
            Fixtures.ALICE,
            Fixtures.ALICE_FOCUS,
            WED_NINE,
            Fixtures.OIL_CHANGE);
    String technician =
        jdbc.queryForObject(
            "SELECT technician_id::text FROM appointment WHERE id = ?::uuid", String.class, id);

    JsonPath json =
        Dispatch.schedule(Fixtures.RIVERSIDE, WEDNESDAY, "TECHNICIAN")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertThat(json.getString("groupBy")).isEqualTo("TECHNICIAN");
    assertThat(json.getList("lanes.type", String.class)).containsOnly("TECHNICIAN");
    assertThat(json.getList("lanes.id", String.class))
        .containsExactlyInAnyOrder(
            Fixtures.SAM.toString(),
            Fixtures.LEE.toString(),
            Fixtures.ANA.toString(),
            Fixtures.TOM.toString());
    assertThat(item(json, "APPOINTMENT")).containsEntry("laneId", technician);
  }

  @Test
  void closedDayHasNoOpeningHours() {
    LocalDate sunday = next(DayOfWeek.SUNDAY);

    JsonPath json =
        Dispatch.schedule(Fixtures.RIVERSIDE, sunday, null)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertThat(json.getMap("$")).containsEntry("openingHours", null);
    assertThat(json.getList("items")).isEmpty();
  }

  @Test
  void foreignDealership403() {
    given()
        .headers(Headers.dispatcher(UUID.randomUUID(), Fixtures.HUDSON))
        .queryParam("date", WEDNESDAY.toString())
        .get(Dispatch.SCHEDULE, Fixtures.RIVERSIDE)
        .then()
        .statusCode(403)
        .contentType("application/problem+json");
  }

  @Test
  void customerRole403() {
    given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .queryParam("date", WEDNESDAY.toString())
        .get(Dispatch.SCHEDULE, Fixtures.RIVERSIDE)
        .then()
        .statusCode(403);
  }

  @Test
  void missingOrMalformedParametersAre400() {
    Dispatch.as(Fixtures.RIVERSIDE)
        .get(Dispatch.SCHEDULE, Fixtures.RIVERSIDE)
        .then()
        .statusCode(400);
    Dispatch.schedule(Fixtures.RIVERSIDE, WEDNESDAY, "CAR").then().statusCode(400);
    Dispatch.as(Fixtures.RIVERSIDE)
        .queryParam("date", "02/11/2026")
        .get(Dispatch.SCHEDULE, Fixtures.RIVERSIDE)
        .then()
        .statusCode(400);
  }
}
