package com.keyloop.scheduler.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An appointment as returned by the API (spec 14.5); times are UTC instants. */
public record AppointmentView(
    UUID id,
    String status,
    long version,
    Dealership dealership,
    Vehicle vehicle,
    List<Service> services,
    Instant start,
    Instant end,
    ServiceBay serviceBay,
    Technician technician) {

  /** The dealership with its IANA time zone. */
  public record Dealership(UUID id, String name, String timeZone) {}

  /** The vehicle being serviced. */
  public record Vehicle(UUID id, String registration, String make, String model) {}

  /** One booked service, in booking order. */
  public record Service(String code, String name, int durationMinutes) {}

  /** The allocated bay. */
  public record ServiceBay(UUID id, String name) {}

  /** The allocated technician. */
  public record Technician(UUID id, String displayName) {}
}
