package com.keyloop.scheduler.support;

import static io.restassured.RestAssured.given;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Helpers for slot-hold integration tests. */
public final class Holds {

  public static final String ME = "/api/v1/me/holds";
  public static final String AVAILABILITY = "/api/v1/dealerships/{id}/availability";

  private Holds() {}

  /** A {@code POST /me/holds} body. */
  public static Map<String, Object> body(
      UUID dealership, UUID vehicle, Instant start, UUID... services) {
    Map<String, Object> body = new HashMap<>();
    body.put("dealershipId", dealership.toString());
    body.put("vehicleId", vehicle.toString());
    body.put("serviceTypeIds", Arrays.stream(services).map(UUID::toString).toList());
    body.put("start", start.toString());
    return body;
  }

  /** Requests a hold as the given login subject. */
  public static Response hold(
      UUID subject, UUID dealership, UUID vehicle, Instant start, UUID... services) {
    return given()
        .headers(Headers.customer(subject))
        .contentType(ContentType.JSON)
        .body(body(dealership, vehicle, start, services))
        .post(ME);
  }

  /** Confirms a hold by booking with its id on {@code /me/appointments}. */
  public static Response confirm(
      UUID subject, String holdId, UUID dealership, UUID vehicle, Instant start, UUID... services) {
    Map<String, Object> body = Bookings.body(dealership, vehicle, start, services);
    body.put("holdId", holdId);
    return Bookings.book(subject, body, "confirm-" + UUID.randomUUID());
  }

  /** Requests a hold for rescheduling the given appointment. */
  public static Response holdToReschedule(
      UUID subject,
      String appointmentId,
      UUID dealership,
      UUID vehicle,
      Instant start,
      UUID... services) {
    Map<String, Object> body = body(dealership, vehicle, start, services);
    body.put("rescheduleOf", appointmentId);
    return given()
        .headers(Headers.customer(subject))
        .contentType(ContentType.JSON)
        .body(body)
        .post(ME);
  }

  /** {@code GET /me/holds/{id}}. */
  public static Response get(UUID subject, String holdId) {
    return given().headers(Headers.customer(subject)).get(ME + "/" + holdId);
  }

  /** {@code DELETE /me/holds/{id}}. */
  public static Response delete(UUID subject, String holdId) {
    return given().headers(Headers.customer(subject)).delete(ME + "/" + holdId);
  }

  /** Bookable starts of one day as the given customer sees them. */
  public static List<String> starts(
      UUID subject, UUID dealership, LocalDate day, UUID... services) {
    return given()
        .headers(Headers.customer(subject))
        .queryParam(
            "serviceTypeIds",
            Arrays.stream(services).map(UUID::toString).collect(Collectors.joining(",")))
        .queryParam("from", day.toString())
        .queryParam("to", day.toString())
        .get(AVAILABILITY, dealership)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("days[0].starts", String.class);
  }
}
