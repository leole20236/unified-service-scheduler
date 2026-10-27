package com.keyloop.scheduler.outbox;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL for the transactional outbox. */
@Repository
public class OutboxRepository {

  private final JdbcTemplate jdbc;

  /** Creates the repository over the routing data source. */
  public OutboxRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts one event. The envelope's {@code time} is set to the transaction's {@code now()} in UTC
   * {@code Z} form, the same instant as {@code occurred_at}.
   */
  public void insert(
      UUID id,
      String aggregateType,
      UUID aggregateId,
      long aggregateVersion,
      UUID dealershipId,
      String eventType,
      String payloadJson) {
    jdbc.update(
        """
        INSERT INTO outbox_event (id, aggregate_type, aggregate_id, aggregate_version,
                                  dealership_id, event_type, payload, occurred_at)
        VALUES (?, ?, ?, ?, ?, ?,
                jsonb_set(?::jsonb, '{time}',
                          to_jsonb(to_char(now() AT TIME ZONE 'UTC',
                                           'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))),
                now())
        """,
        id,
        aggregateType,
        aggregateId,
        aggregateVersion,
        dealershipId,
        eventType,
        payloadJson);
  }
}
