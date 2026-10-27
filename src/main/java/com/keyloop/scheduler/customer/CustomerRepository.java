package com.keyloop.scheduler.customer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** SQL for the customer table. */
@Repository
public class CustomerRepository {

  private static final RowMapper<CustomerView> MAPPER =
      (rs, i) ->
          new CustomerView(
              rs.getObject("id", UUID.class),
              rs.getString("display_name"),
              rs.getString("email"),
              rs.getString("phone"),
              List.of());

  private final JdbcTemplate jdbc;

  /** Creates the repository over the routing data source. */
  public CustomerRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Finds the customer id of a login subject. */
  public Optional<UUID> findIdBySubject(UUID subject) {
    return jdbc
        .query(
            "SELECT id FROM customer WHERE idp_subject = ?",
            (rs, i) -> rs.getObject("id", UUID.class),
            subject)
        .stream()
        .findFirst();
  }

  /** Creates the self-service profile unless a concurrent call already did. */
  public void insertForSubject(UUID id, UUID subject, UUID createdBy) {
    jdbc.update(
        """
        INSERT INTO customer (id, idp_subject, display_name, created_by)
        VALUES (?, ?, 'Customer', ?) ON CONFLICT (idp_subject) DO NOTHING
        """,
        id,
        subject,
        createdBy);
  }

  /** Creates a customer without a login. */
  public void insertWalkIn(
      UUID id, String displayName, String email, String phone, UUID createdBy) {
    jdbc.update(
        "INSERT INTO customer (id, display_name, email, phone, created_by) VALUES (?, ?, ?, ?, ?)",
        id,
        displayName,
        email,
        phone,
        createdBy);
  }

  /** Whether the customer exists. */
  public boolean exists(UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM customer WHERE id = ?)", Boolean.class, id));
  }

  /** Loads a customer (without vehicles). */
  public Optional<CustomerView> find(UUID id) {
    return jdbc
        .query("SELECT id, display_name, email, phone FROM customer WHERE id = ?", MAPPER, id)
        .stream()
        .findFirst();
  }

  /**
   * Prefix search over email (case-insensitive), phone and vehicle registration, written so the
   * text_pattern_ops indexes apply.
   */
  public List<CustomerView> search(String query, int limit) {
    String like = escapeLike(query);
    return jdbc.query(
        """
        SELECT c.id, c.display_name, c.email, c.phone FROM customer c
        WHERE lower(c.email) LIKE lower(?) || '%'
           OR c.phone LIKE ? || '%'
           OR c.id IN (SELECT v.customer_id FROM vehicle v
                       WHERE upper(v.registration) LIKE upper(?) || '%')
        ORDER BY c.display_name, c.id
        LIMIT ?
        """,
        MAPPER, like, like, like, limit);
  }

  static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
