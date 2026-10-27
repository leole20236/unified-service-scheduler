package com.keyloop.scheduler.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * CloudEvents-style envelope stored in {@code outbox_event.payload} (spec 10.2). {@code time} is
 * stamped by the database ({@code now()} of the writing transaction) when the row is inserted.
 */
public record EventEnvelope(
    UUID id,
    String type,
    int schemaVersion,
    long aggregateVersion,
    String source,
    Instant time,
    UUID dealershipId,
    Object data) {

  /** Current envelope schema version. */
  public static final int SCHEMA_VERSION = 1;
}
