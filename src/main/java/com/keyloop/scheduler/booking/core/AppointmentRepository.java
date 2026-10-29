package com.keyloop.scheduler.booking.core;

import com.keyloop.scheduler.booking.domain.AppointmentProgress;
import com.keyloop.scheduler.booking.domain.AppointmentState;
import com.keyloop.scheduler.booking.domain.AppointmentStatus;
import com.keyloop.scheduler.booking.domain.NewAppointment;
import com.keyloop.scheduler.booking.domain.StatusChange;
import com.keyloop.scheduler.booking.domain.Transitioned;
import com.keyloop.scheduler.catalog.ServiceTypeView;
import com.keyloop.scheduler.common.time.TimeRange;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
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

  /** The columns change decisions are made on, or empty if there is no such appointment. */
  public Optional<AppointmentState> findState(UUID id) {
    return jdbc
        .query(
            """
            SELECT id, customer_id, dealership_id, status, version, start_at, end_at,
                   service_bay_id, technician_id
            FROM appointment WHERE id = ?
            """,
            (rs, row) ->
                new AppointmentState(
                    rs.getObject("id", UUID.class),
                    rs.getObject("customer_id", UUID.class),
                    rs.getObject("dealership_id", UUID.class),
                    AppointmentStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"),
                    new TimeRange(
                        rs.getTimestamp("start_at").toInstant(),
                        rs.getTimestamp("end_at").toInstant()),
                    rs.getObject("service_bay_id", UUID.class),
                    rs.getObject("technician_id", UUID.class)),
            id)
        .stream()
        .findFirst();
  }

  /** The appointment's service type ids in booking order. */
  public List<UUID> serviceTypeIds(UUID id) {
    return jdbc.queryForList(
        "SELECT service_type_id FROM appointment_service WHERE appointment_id = ?"
            + " ORDER BY position",
        UUID.class,
        id);
  }

  /**
   * Cancels in one conditional statement (spec 8.4): the customer's own BOOKED appointment, at
   * least 24 hours before it starts by the database clock and, if given, at the expected version.
   * Returns the new version, or empty if no row qualified.
   */
  public OptionalLong markCancelled(UUID id, UUID customerId, OptionalLong expectedVersion) {
    return single(
        jdbc.queryForList(
            """
            UPDATE appointment SET status = 'CANCELLED', version = version + 1, updated_at = now()
            WHERE id = ? AND customer_id = ? AND status = 'BOOKED'
              AND start_at - now() >= interval '24 hours'
              AND (?::bigint IS NULL OR version = ?::bigint)
            RETURNING version
            """,
            Long.class,
            id,
            customerId,
            expectedVersion.isPresent() ? expectedVersion.getAsLong() : null,
            expectedVersion.isPresent() ? expectedVersion.getAsLong() : null));
  }

  /**
   * Moves the customer's own BOOKED appointment to a new pair and period, only at the expected
   * version and at least 24 hours before it starts (database clock). Returns the new version, or
   * empty if no row qualified.
   */
  public OptionalLong markRescheduled(
      UUID id,
      UUID customerId,
      long expectedVersion,
      UUID serviceBayId,
      UUID technicianId,
      TimeRange period) {
    return single(
        jdbc.queryForList(
            """
            UPDATE appointment
            SET service_bay_id = ?, technician_id = ?, start_at = ?, end_at = ?,
                version = version + 1, updated_at = now()
            WHERE id = ? AND customer_id = ? AND version = ? AND status = 'BOOKED'
              AND start_at - now() >= interval '24 hours'
            RETURNING version
            """,
            Long.class,
            serviceBayId,
            technicianId,
            Timestamp.from(period.start()),
            Timestamp.from(period.end()),
            id,
            customerId,
            expectedVersion));
  }

  /** Planned minutes (sum of the booked services' snapshot durations) and minutes worked. */
  public Optional<AppointmentProgress> progress(UUID id) {
    return jdbc
        .query(
            """
            SELECT a.worked_minutes,
                   (SELECT coalesce(sum(s.duration_minutes), 0) FROM appointment_service s
                    WHERE s.appointment_id = a.id) AS planned_minutes
            FROM appointment a WHERE a.id = ?
            """,
            (rs, row) ->
                new AppointmentProgress(rs.getInt("planned_minutes"), rs.getInt("worked_minutes")),
            id)
        .stream()
        .findFirst();
  }

  /** Minutes of the current stint, by the database clock (0 when no stint is running). */
  private static final String STINT =
      "coalesce(greatest(0, floor(extract(epoch FROM now() - started_at) / 60)), 0)::int";

  /**
   * One conditional status UPDATE (spec 8.4): only from an allowed status, at the given version,
   * inside the dealership, and for NO_SHOW only 30 minutes after the start by the database clock.
   * Returns the new version and period, or empty if no row qualified.
   */
  public Optional<Transitioned> transition(
      UUID id, UUID dealershipId, long version, StatusChange change) {
    StringBuilder sql =
        new StringBuilder("UPDATE appointment SET status = ?, version = version + 1,")
            .append(" updated_at = now()");
    final List<Object> args = new ArrayList<>(List.of(change.to().name()));
    if (change.startNow()) {
      sql.append(", started_at = now()");
    }
    if (change.addStint()) {
      sql.append(", worked_minutes = worked_minutes + ").append(STINT);
    }
    if (change.endNowIfEarlier()) {
      sql.append(", end_at = CASE WHEN now() > start_at AND now() < end_at THEN now()")
          .append(" ELSE end_at END");
    }
    if (change.holdReason() != null) {
      sql.append(", hold_reason = ?, started_at = NULL");
      args.add(change.holdReason().name());
    }
    StatusChange.Resume resume = change.resume();
    if (resume != null) {
      sql.append(", hold_reason = NULL, started_at = ?, service_bay_id = ?, technician_id = ?,")
          .append(" start_at = least(start_at, ?), end_at = ?");
      args.add(Timestamp.from(resume.period().start()));
      args.add(resume.serviceBayId());
      args.add(resume.technicianId());
      args.add(Timestamp.from(resume.period().start()));
      args.add(Timestamp.from(resume.period().end()));
    }
    sql.append(" WHERE id = ? AND dealership_id = ? AND version = ?")
        .append(" AND status = ANY (?::text[])");
    args.addAll(List.of(id, dealershipId, version));
    args.add(change.from().stream().map(Enum::name).toArray(String[]::new));
    if (change.requireNoShowGrace()) {
      sql.append(" AND now() >= start_at + interval '30 minutes'");
    }
    sql.append(" RETURNING version, start_at, end_at");
    return jdbc
        .query(
            sql.toString(),
            (rs, row) ->
                new Transitioned(
                    rs.getLong("version"),
                    new TimeRange(
                        rs.getTimestamp("start_at").toInstant(),
                        rs.getTimestamp("end_at").toInstant())),
            args.toArray())
        .stream()
        .findFirst();
  }

  /**
   * A dispatcher move or resize (spec 8.4): new resources and period, only at the expected version,
   * inside the dealership and while the job is BOOKED, CHECKED_IN or IN_PROGRESS. Returns the new
   * version, or empty if no row qualified.
   */
  public OptionalLong markMoved(
      UUID id,
      UUID dealershipId,
      long expectedVersion,
      UUID serviceBayId,
      UUID technicianId,
      TimeRange period) {
    return single(
        jdbc.queryForList(
            """
            UPDATE appointment
            SET service_bay_id = ?, technician_id = ?, start_at = ?, end_at = ?,
                version = version + 1, updated_at = now()
            WHERE id = ? AND dealership_id = ? AND version = ?
              AND status IN ('BOOKED', 'CHECKED_IN', 'IN_PROGRESS')
            RETURNING version
            """,
            Long.class,
            serviceBayId,
            technicianId,
            Timestamp.from(period.start()),
            Timestamp.from(period.end()),
            id,
            dealershipId,
            expectedVersion));
  }

  private static OptionalLong single(List<Long> versions) {
    return versions.isEmpty() ? OptionalLong.empty() : OptionalLong.of(versions.get(0));
  }
}
