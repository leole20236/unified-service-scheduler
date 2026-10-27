package com.keyloop.scheduler.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Read-only catalog queries; served by the replica. */
@Repository
public class CatalogRepository {

  private final JdbcTemplate jdbc;

  /** Creates the repository over the routing data source. */
  public CatalogRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Loads a dealership's full catalog, or empty when the dealership does not exist. */
  @Transactional(readOnly = true)
  public Optional<ResourceCatalog> load(UUID dealershipId) {
    List<DealershipView> found = dealerships("WHERE d.id = ?", dealershipId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    List<ServiceTypeView> services =
        jdbc.query(
            """
            SELECT s.id, s.code, s.name,
                   coalesce(ds.duration_override_minutes, s.duration_minutes) AS duration,
                   s.required_skill
            FROM dealership_service ds JOIN service_type s ON s.id = ds.service_type_id
            WHERE ds.dealership_id = ? ORDER BY s.code
            """,
            (rs, i) ->
                new ServiceTypeView(
                    rs.getObject("id", UUID.class),
                    rs.getString("code"),
                    rs.getString("name"),
                    rs.getInt("duration"),
                    rs.getString("required_skill")),
            dealershipId);
    List<BayView> bays =
        jdbc.query(
            "SELECT id, name FROM service_bay WHERE dealership_id = ? AND active ORDER BY name, id",
            (rs, i) -> new BayView(rs.getObject("id", UUID.class), rs.getString("name")),
            dealershipId);
    List<TechnicianView> technicians = technicians(dealershipId);
    return Optional.of(new ResourceCatalog(found.get(0), services, bays, technicians));
  }

  /** Lists all dealerships ordered by name. */
  @Transactional(readOnly = true)
  public List<DealershipView> listDealerships() {
    return dealerships("");
  }

  private List<TechnicianView> technicians(UUID dealershipId) {
    Map<UUID, String> names = new java.util.LinkedHashMap<>();
    Map<UUID, TreeSet<String>> skills = new java.util.HashMap<>();
    jdbc.query(
        """
        SELECT t.id, t.display_name, ts.skill_code
        FROM technician t LEFT JOIN technician_skill ts ON ts.technician_id = t.id
        WHERE t.dealership_id = ? AND t.active ORDER BY t.display_name, t.id
        """,
        (ResultSet rs) -> {
          UUID id = rs.getObject("id", UUID.class);
          names.put(id, rs.getString("display_name"));
          TreeSet<String> set = skills.computeIfAbsent(id, k -> new TreeSet<>());
          String skill = rs.getString("skill_code");
          if (skill != null) {
            set.add(skill);
          }
        },
        dealershipId);
    List<TechnicianView> result = new ArrayList<>();
    names.forEach((id, name) -> result.add(new TechnicianView(id, name, skills.get(id))));
    return result;
  }

  private List<DealershipView> dealerships(String where, Object... args) {
    Map<UUID, Map<DayOfWeek, OpeningHours>> hours = new java.util.HashMap<>();
    jdbc.query(
        "SELECT dealership_id, day_of_week, opens_at, closes_at FROM opening_hours oh"
            + " JOIN dealership d ON d.id = oh.dealership_id "
            + where,
        (ResultSet rs) -> {
          hours
              .computeIfAbsent(
                  rs.getObject("dealership_id", UUID.class), k -> new EnumMap<>(DayOfWeek.class))
              .put(
                  DayOfWeek.of(rs.getInt("day_of_week")),
                  new OpeningHours(
                      rs.getObject("opens_at", LocalTime.class),
                      rs.getObject("closes_at", LocalTime.class)));
        },
        args);
    return jdbc.query(
        "SELECT d.id, d.name, d.zone_id, d.bay_buffer_minutes FROM dealership d "
            + where
            + " ORDER BY d.name, d.id",
        (rs, i) -> dealership(rs, hours),
        args);
  }

  private static DealershipView dealership(
      ResultSet rs, Map<UUID, Map<DayOfWeek, OpeningHours>> hours) throws SQLException {
    UUID id = rs.getObject("id", UUID.class);
    return new DealershipView(
        id,
        rs.getString("name"),
        ZoneId.of(rs.getString("zone_id")),
        rs.getInt("bay_buffer_minutes"),
        hours.getOrDefault(id, new EnumMap<>(DayOfWeek.class)));
  }
}
