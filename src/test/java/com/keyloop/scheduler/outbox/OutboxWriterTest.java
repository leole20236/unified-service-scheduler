package com.keyloop.scheduler.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OutboxWriterTest {

  private static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final OutboxRepository repository = mock(OutboxRepository.class);
  private final OutboxWriter writer = new OutboxWriter(repository, JSON, "eu-west");

  @Test
  void appendsEnvelopeWithIdsAndData() throws Exception {
    UUID appointment = UUID.randomUUID();

    writer.append(
        "Appointment",
        appointment,
        Fixtures.RIVERSIDE,
        "AppointmentBooked",
        0,
        Map.of("start", Instant.parse("2026-11-02T09:00:00Z")));

    ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(repository)
        .insert(
            id.capture(),
            eq("Appointment"),
            eq(appointment),
            eq(0L),
            eq(Fixtures.RIVERSIDE),
            eq("AppointmentBooked"),
            payload.capture());
    JsonNode json = JSON.readTree(payload.getValue());
    assertThat(json.get("id").asText()).isEqualTo(id.getValue().toString());
    assertThat(id.getValue().version()).isEqualTo(7);
    assertThat(json.get("type").asText()).isEqualTo("AppointmentBooked");
    assertThat(json.get("schemaVersion").asInt()).isEqualTo(1);
    assertThat(json.get("aggregateVersion").asLong()).isZero();
    assertThat(json.get("source").asText()).isEqualTo("scheduler/eu-west");
    assertThat(json.get("dealershipId").asText()).isEqualTo(Fixtures.RIVERSIDE.toString());
    assertThat(json.get("data").get("start").asText()).isEqualTo("2026-11-02T09:00:00Z");
  }

  @Test
  void unserialisableDataFailsLoudly() throws Exception {
    ObjectMapper broken = mock(ObjectMapper.class);
    when(broken.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
    OutboxWriter failing = new OutboxWriter(repository, broken, "eu-west");

    assertThatThrownBy(
            () ->
                failing.append(
                    "Appointment", UUID.randomUUID(), Fixtures.RIVERSIDE, "X", 1, Map.of()))
        .isInstanceOf(IllegalStateException.class);
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
        .insert(any(), any(), any(), anyLong(), any(), any(), any());
  }
}
