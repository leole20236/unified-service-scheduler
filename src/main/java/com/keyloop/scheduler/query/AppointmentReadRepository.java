package com.keyloop.scheduler.query;

import com.keyloop.scheduler.booking.domain.AppointmentStatus;
import com.keyloop.scheduler.query.CursorCodec.Position;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads appointments as API views (with their services) from whichever database the caller is on.
 */
@Repository
public class AppointmentReadRepository {

  private static final String SELECT =
      """
      SELECT a.id, a.status, a.version, d.id AS dealership_id, d.name AS dealership_name,
             d.zone_id, v.id AS vehicle_id, v.registration, v.make, v.model, a.start_at,
             a.end_at, b.id AS bay_id, b.name AS bay_name, t.id AS technician_id,
             t.display_name AS technician_name
      FROM appointment a
      JOIN dealership d ON d.id = a.dealership_id
      JOIN vehicle v ON v.id = a.vehicle_id
      JOIN service_bay b ON b.id = a.service_bay_id
      JOIN technician t ON t.id = a.technician_id
      """;

  private final JdbcTemplate jdbc;

  /** Creates the repository over the routing data source. */
  public AppointmentReadRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The appointment, whoever owns it. */
  public Optional<AppointmentView> find(UUID id) {
    return withServices(jdbc.query(SELECT + " WHERE a.id = ?", ROW, id)).stream().findFirst();
  }

  /** The appointment if it belongs to the customer. */
  public Optional<AppointmentView> findOwned(UUID customerId, UUID id) {
    return withServices(
            jdbc.query(SELECT + " WHERE a.id = ? AND a.customer_id = ?", ROW, id, customerId))
        .stream()
        .findFirst();
  }

  /**
   * The customer's appointments, newest start first (ties by id), after the cursor position; at
   * most {@code fetch} rows. Served by {@code appointment_customer_timeline}.
   */
  public List<AppointmentView> page(
      UUID customerId,
      Optional<AppointmentStatus> status,
      Optional<Instant> from,
      Optional<Instant> to,
      Optional<Position> after,
      int fetch) {
    StringBuilder sql = new StringBuilder(SELECT).append(" WHERE a.customer_id = ?");
    List<Object> args = new ArrayList<>(List.of(customerId));
    status.ifPresent(
        s -> {
          sql.append(" AND a.status = ?");
          args.add(s.name());
        });
    from.ifPresent(
        t -> {
          sql.append(" AND a.start_at >= ?");
          args.add(Timestamp.from(t));
        });
    to.ifPresent(
        t -> {
          sql.append(" AND a.start_at < ?");
          args.add(Timestamp.from(t));
        });
    after.ifPresent(
        p -> {
          sql.append(" AND (a.start_at < ? OR (a.start_at = ? AND a.id > ?))");
          args.add(Timestamp.from(p.startAt()));
          args.add(Timestamp.from(p.startAt()));
          args.add(p.id());
        });
    sql.append(" ORDER BY a.start_at DESC, a.id LIMIT ?");
    args.add(fetch);
    return withServices(jdbc.query(sql.toString(), ROW, args.toArray()));
  }

  private static final RowMapper<AppointmentRecord> ROW =
      (rs, i) ->
          new AppointmentRecord(
              rs.getObject("id", UUID.class),
              rs.getString("status"),
              rs.getLong("version"),
              rs.getObject("dealership_id", UUID.class),
              rs.getString("dealership_name"),
              rs.getString("zone_id"),
              rs.getObject("vehicle_id", UUID.class),
              rs.getString("registration"),
              rs.getString("make"),
              rs.getString("model"),
              rs.getObject("start_at", OffsetDateTime.class),
              rs.getObject("end_at", OffsetDateTime.class),
              rs.getObject("bay_id", UUID.class),
              rs.getString("bay_name"),
              rs.getObject("technician_id", UUID.class),
              rs.getString("technician_name"),
              List.of());

  private List<AppointmentView> withServices(List<AppointmentRecord> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    String[] ids = rows.stream().map(r -> r.id().toString()).toArray(String[]::new);
    Map<UUID, List<ServiceLine>> lines = new HashMap<>();
    jdbc.query(
        """
        SELECT s.appointment_id, s.position, t.code, t.name, s.duration_minutes
        FROM appointment_service s JOIN service_type t ON t.id = s.service_type_id
        WHERE s.appointment_id = ANY (?::uuid[])
        """,
        rs -> {
          lines
              .computeIfAbsent(rs.getObject("appointment_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new ServiceLine(
                      rs.getInt("position"),
                      rs.getString("code"),
                      rs.getString("name"),
                      rs.getInt("duration_minutes")));
        },
        (Object) ids);
    return rows.stream()
        .map(
            r ->
                AppointmentViewMapper.toView(
                    new AppointmentRecord(
                        r.id(),
                        r.status(),
                        r.version(),
                        r.dealershipId(),
                        r.dealershipName(),
                        r.timeZone(),
                        r.vehicleId(),
                        r.registration(),
                        r.make(),
                        r.model(),
                        r.start(),
                        r.end(),
                        r.serviceBayId(),
                        r.serviceBayName(),
                        r.technicianId(),
                        r.technicianName(),
                        lines.getOrDefault(r.id(), List.of()))))
        .toList();
  }
}
