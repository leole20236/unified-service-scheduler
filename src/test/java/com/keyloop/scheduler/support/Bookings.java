package com.keyloop.scheduler.support;

import static io.restassured.RestAssured.given;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Helpers for booking integration tests. */
public final class Bookings {

  public static final String ME = "/api/v1/me/appointments";
  public static final String DISPATCH = "/api/v1/dealerships/{dealershipId}/appointments";
  public static final ZoneId LONDON = ZoneId.of("Europe/London");
  public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

  private Bookings() {}

  /** A customer with a login and one vehicle. */
  public record Customer(UUID subject, UUID id, UUID vehicleId) {}

  /** The first date at least two days ahead that falls on the given weekday. */
  public static LocalDate next(DayOfWeek day) {
    return LocalDate.now(ZoneOffset.UTC).plusDays(2).with(TemporalAdjusters.nextOrSame(day));
  }

  /** A local wall-clock time as an instant. */
  public static Instant at(LocalDate date, int hour, int minute, ZoneId zone) {
    return date.atTime(hour, minute).atZone(zone).toInstant();
  }

  /** Inserts a customer with a login subject and a vehicle. */
  public static Customer newCustomer(JdbcTemplate jdbc) {
    UUID subject = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    UUID vehicle = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO customer (id, idp_subject, display_name, created_by) VALUES (?, ?, 'C', ?)",
        id,
        subject,
        subject);
    jdbc.update(
        "INSERT INTO vehicle (id, customer_id, vin, registration, make, model)"
            + " VALUES (?, ?, ?, 'REG 1', 'Ford', 'Fiesta')",
        vehicle,
        id,
        vin(vehicle));
    return new Customer(subject, id, vehicle);
  }

  /** Inserts {@code n} customers. */
  public static List<Customer> newCustomers(JdbcTemplate jdbc, int n) {
    return java.util.stream.IntStream.range(0, n).mapToObj(i -> newCustomer(jdbc)).toList();
  }

  private static String vin(UUID vehicle) {
    return vehicle.toString().replace("-", "").substring(0, 17).toUpperCase(java.util.Locale.ROOT);
  }

  /** A customer booking body. */
  public static Map<String, Object> body(
      UUID dealership, UUID vehicle, Instant start, UUID... services) {
    Map<String, Object> body = new HashMap<>();
    body.put("dealershipId", dealership.toString());
    body.put("vehicleId", vehicle.toString());
    body.put("serviceTypeIds", Arrays.stream(services).map(UUID::toString).toList());
    body.put("start", start.toString());
    return body;
  }

  /** Books as the customer on {@code /me/appointments}. */
  public static Response book(Customer customer, Map<String, Object> body, String key) {
    return book(customer.subject(), body, key);
  }

  /** Books as the given login subject on {@code /me/appointments}. */
  public static Response book(UUID subject, Map<String, Object> body, String key) {
    return given()
        .headers(Headers.customer(subject))
        .header("Idempotency-Key", key)
        .contentType(ContentType.JSON)
        .body(body)
        .post(ME);
  }

  /** Blocks a bay for the whole local day so it cannot be allocated. */
  public static void blockBayForDay(
      JdbcTemplate jdbc, UUID dealership, UUID bay, LocalDate date, ZoneId zone) {
    jdbc.update(
        "INSERT INTO resource_reservation (id, dealership_id, service_bay_id, kind, start_at,"
            + " end_at, reason) VALUES (?, ?, ?, 'BLOCK', ?::timestamptz, ?::timestamptz,"
            + " 'test block')",
        UUID.randomUUID(),
        dealership,
        bay,
        date.atStartOfDay(zone).toInstant().toString(),
        date.plusDays(1).atStartOfDay(zone).toInstant().toString());
  }
}
