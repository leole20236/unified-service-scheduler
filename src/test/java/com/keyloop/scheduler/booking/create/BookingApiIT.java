package com.keyloop.scheduler.booking.create;

import static com.keyloop.scheduler.support.Bookings.LONDON;
import static com.keyloop.scheduler.support.Bookings.NEW_YORK;
import static com.keyloop.scheduler.support.Bookings.at;
import static com.keyloop.scheduler.support.Bookings.body;
import static com.keyloop.scheduler.support.Bookings.book;
import static com.keyloop.scheduler.support.Bookings.next;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.support.Bookings;
import com.keyloop.scheduler.support.Bookings.Customer;
import com.keyloop.scheduler.support.Fixtures;
import com.keyloop.scheduler.support.Headers;
import com.keyloop.scheduler.support.IntegrationTest;
import com.keyloop.scheduler.support.NoDoubleBookingAssert;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class BookingApiIT extends IntegrationTest {

  private static final UUID DISPATCHER = UUID.randomUUID();

  @Autowired private ObjectMapper json;

  @BeforeEach
  void configure() {
    RestAssured.port = port;
  }

  @AfterEach
  void invariantsHold() {
    NoDoubleBookingAssert.verify(JdbcClient.create(jdbc));
  }

  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private TimeRange reservation(UUID appointmentId, String column) {
    return jdbc.queryForObject(
        "SELECT start_at, end_at FROM resource_reservation WHERE appointment_id = ? AND active"
            + " AND "
            + column
            + " IS NOT NULL",
        (rs, i) ->
            new TimeRange(
                rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant()),
        appointmentId);
  }

  private TimeRange appointmentPeriod(UUID id) {
    return jdbc.queryForObject(
        "SELECT start_at, end_at FROM appointment WHERE id = ?",
        (rs, i) ->
            new TimeRange(
                rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant()),
        id);
  }

  private static Response dispatch(
      UUID dealership, UUID[] allowed, Map<String, Object> body, String key) {
    return given()
        .headers(Headers.dispatcher(DISPATCHER, allowed))
        .header("Idempotency-Key", key)
        .contentType(ContentType.JSON)
        .body(body)
        .post(Bookings.DISPATCH, dealership);
  }

  private static Map<String, Object> dispatcherBody(
      UUID customer, UUID vehicle, Instant start, UUID technician, UUID... services) {
    Map<String, Object> body = body(Fixtures.RIVERSIDE, vehicle, start, services);
    body.remove("dealershipId");
    body.put("customerId", customer.toString());
    if (technician != null) {
      body.put("technicianId", technician.toString());
    }
    return body;
  }

  @Test
  void customerBooksOilAndBrakes() throws Exception {
    LocalDate monday = next(DayOfWeek.MONDAY);
    Instant start = at(monday, 9, 0, LONDON);

    Response response =
        book(
            Fixtures.ALICE_SUB,
            body(
                Fixtures.RIVERSIDE,
                Fixtures.ALICE_FOCUS,
                start,
                Fixtures.OIL_CHANGE,
                Fixtures.BRAKE_INSPECTION),
            "k-1");

    response
        .then()
        .statusCode(201)
        .header("ETag", "\"0\"")
        .header("Cache-Control", "no-store")
        .body("status", equalTo("BOOKED"))
        .body("version", equalTo(0))
        .body("start", equalTo(start.toString()))
        .body("end", equalTo(start.plus(Duration.ofMinutes(120)).toString()))
        .body("dealership.id", equalTo(Fixtures.RIVERSIDE.toString()))
        .body("dealership.timeZone", equalTo("Europe/London"))
        .body("vehicle.registration", equalTo("ALI 1"))
        .body("services[0].code", equalTo("OIL_CHANGE"))
        .body("services[1].code", equalTo("BRAKE_INSPECTION"))
        .body("services[1].durationMinutes", equalTo(75));
    UUID id = UUID.fromString(response.jsonPath().getString("id"));
    assertThat(id.version()).isEqualTo(7);
    assertThat(response.header("Location")).isEqualTo("/api/v1/me/appointments/" + id);
    assertThat(response.jsonPath().getString("technician.id"))
        .isIn(Fixtures.SAM.toString(), Fixtures.TOM.toString());
    assertThat(response.jsonPath().getString("serviceBay.name")).startsWith("Bay ");

    assertThat(count("appointment")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM resource_reservation WHERE active AND appointment_id = ?",
                Integer.class,
                id))
        .isEqualTo(2);
    TimeRange period = new TimeRange(start, start.plus(Duration.ofMinutes(120)));
    assertThat(appointmentPeriod(id)).isEqualTo(period);
    assertThat(reservation(id, "technician_id")).isEqualTo(period);
    assertThat(reservation(id, "service_bay_id")).isEqualTo(period);
    assertThat(
            jdbc.queryForList(
                "SELECT position FROM appointment_service WHERE appointment_id = ?"
                    + " ORDER BY position",
                Integer.class,
                id))
        .containsExactly(1, 2);

    Map<String, Object> outbox =
        jdbc.queryForMap(
            "SELECT aggregate_type, aggregate_id, aggregate_version, dealership_id, event_type,"
                + " payload::text AS payload FROM outbox_event");
    assertThat(outbox)
        .containsEntry("aggregate_type", "Appointment")
        .containsEntry("aggregate_id", id)
        .containsEntry("aggregate_version", 0L)
        .containsEntry("dealership_id", Fixtures.RIVERSIDE)
        .containsEntry("event_type", "AppointmentBooked");
    JsonNode payload = json.readTree((String) outbox.get("payload"));
    assertThat(payload.get("type").asText()).isEqualTo("AppointmentBooked");
    assertThat(payload.get("aggregateVersion").asLong()).isZero();
    assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(payload.get("source").asText()).isEqualTo("scheduler/eu-west");
    assertThat(payload.get("time").asText()).endsWith("Z");
    JsonNode data = payload.get("data");
    assertThat(data.get("appointmentId").asText()).isEqualTo(id.toString());
    assertThat(data.get("customerId").asText()).isEqualTo(Fixtures.ALICE.toString());
    assertThat(data.get("start").asText()).isEqualTo(start.toString());
    assertThat(data.get("timeZone").asText()).isEqualTo("Europe/London");
    assertThat(data.get("status").asText()).isEqualTo("BOOKED");
    assertThat(data.get("serviceTypeCodes").toString())
        .isEqualTo("[\"OIL_CHANGE\",\"BRAKE_INSPECTION\"]");
    assertThat(payload.toString()).doesNotContain("ALI 1");
  }

  @Test
  void appointmentPeriodMatchesReservations() {
    LocalDate tuesday = next(DayOfWeek.TUESDAY);
    Instant start = at(tuesday, 9, 0, NEW_YORK);

    Response response =
        book(
            Fixtures.ALICE_SUB,
            body(Fixtures.HUDSON, Fixtures.ALICE_FOCUS, start, Fixtures.OIL_CHANGE),
            "k-hudson");

    response.then().statusCode(201);
    UUID id = UUID.fromString(response.jsonPath().getString("id"));
    TimeRange period = appointmentPeriod(id);
    assertThat(period).isEqualTo(new TimeRange(start, start.plus(Duration.ofMinutes(45))));
    assertThat(reservation(id, "technician_id")).isEqualTo(period);
    assertThat(reservation(id, "service_bay_id"))
        .isEqualTo(period.extendEnd(Duration.ofMinutes(10)));
  }

  @Test
  void someoneElsesVehicleIs404() {
    Instant start = at(next(DayOfWeek.MONDAY), 9, 0, LONDON);

    book(
            Fixtures.ALICE_SUB,
            body(Fixtures.RIVERSIDE, Fixtures.BOB_GOLF, start, Fixtures.OIL_CHANGE),
            "k-bob")
        .then()
        .statusCode(404)
        .body("code", equalTo("NOT_FOUND"));
    assertThat(count("appointment")).isZero();
  }

  @Test
  void missingIdempotencyKeyIs428() {
    Instant start = at(next(DayOfWeek.MONDAY), 9, 0, LONDON);

    given()
        .headers(Headers.customer(Fixtures.ALICE_SUB))
        .contentType(ContentType.JSON)
        .body(body(Fixtures.RIVERSIDE, Fixtures.ALICE_FOCUS, start, Fixtures.OIL_CHANGE))
        .post(Bookings.ME)
        .then()
        .statusCode(428)
        .body("code", equalTo("PRECONDITION_REQUIRED"));
    assertThat(count("appointment")).isZero();
  }

  @Test
  void overlongIdempotencyKeyAndNotesAre400() {
    Instant start = at(next(DayOfWeek.MONDAY), 9, 0, LONDON);
    Map<String, Object> body =
        body(Fixtures.RIVERSIDE, Fixtures.ALICE_FOCUS, start, Fixtures.OIL_CHANGE);

    book(Fixtures.ALICE_SUB, body, "k".repeat(65))
        .then()
        .statusCode(400)
        .body("code", equalTo("VALIDATION_ERROR"));
    Map<String, Object> notes = new HashMap<>(body);
    notes.put("customerNotes", "n".repeat(501));
    book(Fixtures.ALICE_SUB, notes, "k-notes")
        .then()
        .statusCode(400)
        .body("errors[0].field", equalTo("customerNotes"));
    Map<String, Object> noDealership = new HashMap<>(body);
    noDealership.remove("dealershipId");
    book(Fixtures.ALICE_SUB, noDealership, "k-nod")
        .then()
        .statusCode(400)
        .body("errors[0].field", equalTo("dealershipId"));
    assertThat(count("appointment")).isZero();
  }

  @Test
  void fullSlotReturns409WithAlternatives() {
    LocalDate day = next(DayOfWeek.WEDNESDAY);
    Customer other = Bookings.newCustomer(jdbc);
    book(
            Fixtures.ALICE_SUB,
            body(Fixtures.SOLO, Fixtures.ALICE_FOCUS, at(day, 9, 0, LONDON), Fixtures.OIL_CHANGE),
            "k-a")
        .then()
        .statusCode(201);

    Response conflict =
        book(
            other,
            body(Fixtures.SOLO, other.vehicleId(), at(day, 9, 30, LONDON), Fixtures.OIL_CHANGE),
            "k-b");

    conflict
        .then()
        .statusCode(409)
        .contentType("application/problem+json")
        .body("code", equalTo("SLOT_UNAVAILABLE"))
        .body("$", not(hasKey("conflicts")));
    List<String> alternatives = conflict.jsonPath().getList("alternatives");
    assertThat(alternatives).hasSize(5).allMatch(a -> a.endsWith("Z"));
    assertThat(Instant.parse(alternatives.get(0))).isEqualTo(at(day, 9, 45, LONDON));
    assertThat(conflict.jsonPath().getString("detail"))
        .startsWith("No qualified technician and bay are free at 09:30 on ")
        .endsWith("(Europe/London).");
  }

  @Test
  void noOutboxRowOnConflict() {
    LocalDate day = next(DayOfWeek.WEDNESDAY);
    Customer other = Bookings.newCustomer(jdbc);
    book(
            Fixtures.ALICE_SUB,
            body(Fixtures.SOLO, Fixtures.ALICE_FOCUS, at(day, 9, 0, LONDON), Fixtures.OIL_CHANGE),
            "k-a")
        .then()
        .statusCode(201);

    book(
            other,
            body(Fixtures.SOLO, other.vehicleId(), at(day, 9, 0, LONDON), Fixtures.OIL_CHANGE),
            "k-b")
        .then()
        .statusCode(409);

    assertThat(count("outbox_event")).isEqualTo(1);
    assertThat(count("appointment")).isEqualTo(1);
    assertThat(count("idempotency_key")).isEqualTo(1);
    assertThat(count("resource_reservation")).isEqualTo(2);
  }

  @Test
  void dispatcherBooksWalkInWithPinnedTechnician() {
    UUID customer =
        UUID.fromString(
            given()
                .headers(Headers.dispatcher(DISPATCHER, Fixtures.RIVERSIDE))
                .contentType(ContentType.JSON)
                .body(Map.of("displayName", "Walk In", "phone", "+44 7700 900123"))
                .post("/api/v1/dealerships/{d}/customers", Fixtures.RIVERSIDE)
                .then()
                .statusCode(201)
                .extract()
                .path("id"));
    UUID vehicle =
        UUID.fromString(
            given()
                .headers(Headers.dispatcher(DISPATCHER, Fixtures.RIVERSIDE))
                .contentType(ContentType.JSON)
                .body(
                    Map.of(
                        "vin",
                        "WVWZZZ1KZAW000777",
                        "registration",
                        "WLK 1",
                        "make",
                        "Kia",
                        "model",
                        "Ceed",
                        "modelYear",
                        2019,
                        "mileage",
                        1000))
                .post(
                    "/api/v1/dealerships/{d}/customers/{c}/vehicles", Fixtures.RIVERSIDE, customer)
                .then()
                .statusCode(201)
                .extract()
                .path("id"));
    Instant start = at(next(DayOfWeek.THURSDAY), 10, 0, LONDON);

    Response response =
        dispatch(
            Fixtures.RIVERSIDE,
            new UUID[] {Fixtures.RIVERSIDE},
            dispatcherBody(customer, vehicle, start, Fixtures.TOM, Fixtures.DIAGNOSTIC),
            "k-walk");

    response
        .then()
        .statusCode(201)
        .header("ETag", "\"0\"")
        .body("technician.id", equalTo(Fixtures.TOM.toString()))
        .body("technician.displayName", equalTo("Tom"))
        .body("vehicle.registration", equalTo("WLK 1"));
    String id = response.jsonPath().getString("id");
    assertThat(response.header("Location"))
        .isEqualTo("/api/v1/dealerships/" + Fixtures.RIVERSIDE + "/appointments/" + id);
    assertThat(
            jdbc.queryForObject(
                "SELECT created_by FROM appointment WHERE id = ?::uuid", UUID.class, id))
        .isEqualTo(DISPATCHER);
  }

  @Test
  void dispatcherPinsUnqualifiedTechnician422() {
    Instant start = at(next(DayOfWeek.THURSDAY), 10, 0, LONDON);

    dispatch(
            Fixtures.RIVERSIDE,
            new UUID[] {Fixtures.RIVERSIDE},
            dispatcherBody(
                Fixtures.ALICE,
                Fixtures.ALICE_FOCUS,
                start,
                Fixtures.LEE,
                Fixtures.BRAKE_INSPECTION),
            "k-lee")
        .then()
        .statusCode(422)
        .body("code", equalTo("TECHNICIAN_NOT_QUALIFIED"));
    assertThat(count("appointment")).isZero();
  }

  @Test
  void dispatcherPinsTechnicianOfAnotherDealership404() {
    Instant start = at(next(DayOfWeek.THURSDAY), 10, 0, LONDON);

    dispatch(
            Fixtures.RIVERSIDE,
            new UUID[] {Fixtures.RIVERSIDE},
            dispatcherBody(
                Fixtures.ALICE, Fixtures.ALICE_FOCUS, start, Fixtures.JO, Fixtures.OIL_CHANGE),
            "k-jo")
        .then()
        .statusCode(404)
        .body("code", equalTo("NOT_FOUND"))
        .body("detail", equalTo("Technician not found."));
  }

  @Test
  void dispatcherConflictListsConflictingAppointment() {
    Instant start = at(next(DayOfWeek.THURSDAY), 10, 0, LONDON);
    String first =
        dispatch(
                Fixtures.RIVERSIDE,
                new UUID[] {Fixtures.RIVERSIDE},
                dispatcherBody(
                    Fixtures.ALICE, Fixtures.ALICE_FOCUS, start, Fixtures.TOM, Fixtures.OIL_CHANGE),
                "k-1")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    dispatch(
            Fixtures.RIVERSIDE,
            new UUID[] {Fixtures.RIVERSIDE},
            dispatcherBody(
                Fixtures.BOB, Fixtures.BOB_GOLF, start, Fixtures.TOM, Fixtures.OIL_CHANGE),
            "k-2")
        .then()
        .statusCode(409)
        .body("code", equalTo("SLOT_UNAVAILABLE"))
        .body("conflicts", equalTo(List.of(first)));
  }

  @Test
  void dispatcherForeignDealership403() {
    Instant start = at(next(DayOfWeek.THURSDAY), 10, 0, LONDON);

    dispatch(
            Fixtures.RIVERSIDE,
            new UUID[] {Fixtures.HUDSON},
            dispatcherBody(Fixtures.ALICE, Fixtures.ALICE_FOCUS, start, null, Fixtures.OIL_CHANGE),
            "k-403")
        .then()
        .statusCode(403)
        .body("code", equalTo("FORBIDDEN"));
    assertThat(count("appointment")).isZero();
  }

  @Test
  void dispatcherMustNameTheCustomer() {
    Instant start = at(next(DayOfWeek.THURSDAY), 10, 0, LONDON);
    Map<String, Object> body =
        dispatcherBody(Fixtures.ALICE, Fixtures.ALICE_FOCUS, start, null, Fixtures.OIL_CHANGE);
    body.remove("customerId");

    dispatch(Fixtures.RIVERSIDE, new UUID[] {Fixtures.RIVERSIDE}, body, "k-noc")
        .then()
        .statusCode(400)
        .body("errors[0].field", equalTo("customerId"));
  }

  @Test
  void customerEndpointNeedsCustomerRole() {
    Instant start = at(next(DayOfWeek.MONDAY), 9, 0, LONDON);

    given()
        .headers(Headers.dispatcher(DISPATCHER, Fixtures.RIVERSIDE))
        .header("Idempotency-Key", "k")
        .contentType(ContentType.JSON)
        .body(body(Fixtures.RIVERSIDE, Fixtures.ALICE_FOCUS, start, Fixtures.OIL_CHANGE))
        .post(Bookings.ME)
        .then()
        .statusCode(403);
  }
}
