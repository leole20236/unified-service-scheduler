package com.keyloop.scheduler.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyloop.scheduler.common.id.Ids;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends domain events to the outbox inside the caller's transaction, so an event exists if and
 * only if the state change committed (spec 10.1).
 */
@Component
public class OutboxWriter {

  private final OutboxRepository repository;
  private final ObjectMapper json;
  private final String source;

  /** Creates the writer; {@code region} names the event source, e.g. {@code scheduler/eu-west}. */
  public OutboxWriter(
      OutboxRepository repository,
      ObjectMapper json,
      @Value("${app.region:eu-west}") String region) {
    this.repository = repository;
    this.json = json;
    this.source = "scheduler/" + region;
  }

  /** Writes one event; fails unless a transaction is already active. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      String aggregateType,
      UUID aggregateId,
      UUID dealershipId,
      String eventType,
      long aggregateVersion,
      Object data) {
    UUID id = Ids.newId();
    EventEnvelope envelope =
        new EventEnvelope(
            id,
            eventType,
            EventEnvelope.SCHEMA_VERSION,
            aggregateVersion,
            source,
            null,
            dealershipId,
            data);
    String payload;
    try {
      payload = json.writeValueAsString(envelope);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Event " + eventType + " is not serialisable", e);
    }
    repository.insert(
        id, aggregateType, aggregateId, aggregateVersion, dealershipId, eventType, payload);
  }
}
