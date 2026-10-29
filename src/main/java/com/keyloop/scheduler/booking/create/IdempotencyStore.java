package com.keyloop.scheduler.booking.create;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyloop.scheduler.common.api.BusinessRuleException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.api.RequestInProgressException;
import com.keyloop.scheduler.common.db.PrimaryRead;
import com.keyloop.scheduler.common.db.SqlStates;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotency keys (spec 8.5). The key row is the first insert of a booking transaction, so an
 * identical concurrent request blocks on the primary-key index until the first commits, then fails
 * with unique_violation and replays the stored response from a new transaction.
 */
@Component
public class IdempotencyStore {

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  /** Creates the store over the routing data source. */
  public IdempotencyStore(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * The stored response of a completed request with this key, read from the primary in its own
   * transaction. Empty when the key is unused. A different request hash is a 422
   * IDEMPOTENCY_KEY_REUSED; a key without a response yet is a 409 REQUEST_IN_PROGRESS.
   */
  @PrimaryRead
  public Optional<StoredResponse> find(UUID userId, String key, String requestHash) {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT request_hash, response_code, response_body::text AS body"
                + " FROM idempotency_key WHERE user_id = ? AND key = ?",
            userId,
            key);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Object> row = rows.get(0);
    if (!requestHash.equals(row.get("request_hash"))) {
      throw new BusinessRuleException(
          ProblemCodes.IDEMPOTENCY_KEY_REUSED,
          "This Idempotency-Key was already used with a different request body.");
    }
    if (row.get("response_code") == null) {
      throw inProgress();
    }
    return Optional.of(
        new StoredResponse((Integer) row.get("response_code"), (String) row.get("body")));
  }

  /**
   * First statement of the booking transaction: claims the key. Raises {@link
   * DuplicateRequestException} when another request already committed it.
   */
  public void begin(UUID userId, String key, String requestHash) {
    try {
      jdbc.update(
          "INSERT INTO idempotency_key (key, user_id, request_hash) VALUES (?, ?, ?)",
          key,
          userId,
          requestHash);
    } catch (DataAccessException e) {
      if (SqlStates.has(e, SqlStates.UNIQUE_VIOLATION)) {
        throw new DuplicateRequestException();
      }
      throw e;
    }
  }

  /** Last statement of the booking transaction: stores the response for replays. */
  public void complete(UUID userId, String key, int status, Object body) {
    String bodyJson;
    try {
      bodyJson = json.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Response is not serialisable", e);
    }
    jdbc.update(
        "UPDATE idempotency_key SET response_code = ?, response_body = ?::jsonb"
            + " WHERE user_id = ? AND key = ?",
        status,
        bodyJson,
        userId,
        key);
  }

  /** The 409 for a duplicate whose original has not finished. */
  static RequestInProgressException inProgress() {
    return new RequestInProgressException(
        "A request with this Idempotency-Key is still being processed. Retry shortly.");
  }
}
