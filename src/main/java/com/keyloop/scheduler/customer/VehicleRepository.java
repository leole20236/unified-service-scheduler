package com.keyloop.scheduler.customer;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** SQL for the vehicle table. */
@Repository
public class VehicleRepository {

  private static final String COLUMNS = "id, vin, registration, make, model, model_year, mileage";

  private static final RowMapper<VehicleView> MAPPER =
      (rs, i) ->
          new VehicleView(
              rs.getObject("id", UUID.class),
              rs.getString("vin"),
              rs.getString("registration"),
              rs.getString("make"),
              rs.getString("model"),
              (Integer) rs.getObject("model_year"),
              (Integer) rs.getObject("mileage"));

  private final JdbcTemplate jdbc;

  /** Creates the repository over the routing data source. */
  public VehicleRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts a vehicle; a duplicate (customer, vin) raises DuplicateKeyException. */
  public void insert(UUID id, UUID customerId, NewVehicleRequest r) {
    jdbc.update(
        "INSERT INTO vehicle (id, customer_id, vin, registration, make, model, model_year,"
            + " mileage) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        customerId,
        r.vin(),
        r.registration(),
        r.make(),
        r.model(),
        r.modelYear(),
        r.mileage());
  }

  /** Vehicles of one customer. */
  public List<VehicleView> findByCustomer(UUID customerId) {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM vehicle WHERE customer_id = ? ORDER BY vin",
        MAPPER,
        customerId);
  }

  /** A vehicle only if it belongs to the customer. */
  public Optional<VehicleView> findOwned(UUID customerId, UUID vehicleId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM vehicle WHERE id = ? AND customer_id = ?",
            MAPPER,
            vehicleId,
            customerId)
        .stream()
        .findFirst();
  }

  /** Vehicles of many customers in a single query. */
  public Map<UUID, List<VehicleView>> findByCustomers(Collection<UUID> customerIds) {
    if (customerIds.isEmpty()) {
      return Collections.emptyMap();
    }
    String placeholders = String.join(", ", Collections.nCopies(customerIds.size(), "?"));
    Map<UUID, List<VehicleView>> result = new HashMap<>();
    jdbc.query(
        "SELECT customer_id, "
            + COLUMNS
            + " FROM vehicle WHERE customer_id IN ("
            + placeholders
            + ") ORDER BY vin",
        (ResultSet rs) -> {
          result
              .computeIfAbsent(rs.getObject("customer_id", UUID.class), k -> new ArrayList<>())
              .add(MAPPER.mapRow(rs, 0));
        },
        customerIds.toArray());
    return result;
  }
}
