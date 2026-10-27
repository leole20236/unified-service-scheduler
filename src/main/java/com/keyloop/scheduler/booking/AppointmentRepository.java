package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.ServiceTypeView;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Writes appointments and their services. */
@Repository
public class AppointmentRepository {

  private final JdbcTemplate jdbc;

  /** Creates the repository over the routing data source. */
  public AppointmentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts a BOOKED appointment (version 0) and its services, positions starting at 1. */
  public void insert(NewAppointment a) {
    jdbc.update(
        """
        INSERT INTO appointment (id, dealership_id, customer_id, vehicle_id, service_bay_id,
                                 technician_id, start_at, end_at, status, version,
                                 customer_notes, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'BOOKED', 0, ?, ?)
        """,
        a.id(),
        a.dealershipId(),
        a.customerId(),
        a.vehicleId(),
        a.serviceBayId(),
        a.technicianId(),
        Timestamp.from(a.period().start()),
        Timestamp.from(a.period().end()),
        a.customerNotes(),
        a.createdBy());
    List<Object[]> services = new ArrayList<>();
    int position = 1;
    for (ServiceTypeView s : a.services()) {
      services.add(new Object[] {a.id(), s.id(), position++, s.durationMinutes()});
    }
    jdbc.batchUpdate(
        "INSERT INTO appointment_service (appointment_id, service_type_id, position,"
            + " duration_minutes) VALUES (?, ?, ?, ?)",
        services);
  }
}
