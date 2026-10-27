package com.keyloop.scheduler.query;

import java.util.Comparator;

/** Maps flat appointment data to the API shape: UTC instants, services in booking order. */
public final class AppointmentViewMapper {

  private AppointmentViewMapper() {}

  /** Builds the API view. */
  public static AppointmentView toView(AppointmentRecord r) {
    return new AppointmentView(
        r.id(),
        r.status(),
        r.version(),
        new AppointmentView.Dealership(r.dealershipId(), r.dealershipName(), r.timeZone()),
        new AppointmentView.Vehicle(r.vehicleId(), r.registration(), r.make(), r.model()),
        r.services().stream()
            .sorted(Comparator.comparingInt(ServiceLine::position))
            .map(s -> new AppointmentView.Service(s.code(), s.name(), s.durationMinutes()))
            .toList(),
        r.start().toInstant(),
        r.end().toInstant(),
        new AppointmentView.ServiceBay(r.serviceBayId(), r.serviceBayName()),
        new AppointmentView.Technician(r.technicianId(), r.technicianName()));
  }
}
