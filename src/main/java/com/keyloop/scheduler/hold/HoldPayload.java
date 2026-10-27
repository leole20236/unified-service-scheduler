package com.keyloop.scheduler.hold;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;

/** What a hold was requested for, stored with it so the confirm can be matched (spec 8.6). */
public record HoldPayload(UUID vehicleId, List<UUID> serviceTypeIds) {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Immutable copy. */
  public HoldPayload {
    serviceTypeIds = List.copyOf(serviceTypeIds);
  }

  /** Creates the payload. */
  public static HoldPayload of(UUID vehicleId, List<UUID> serviceTypeIds) {
    return new HoldPayload(vehicleId, serviceTypeIds);
  }

  /** JSON form stored in the hold hash. */
  public String toJson() {
    try {
      return JSON.writeValueAsString(this);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Hold payload is not serialisable", e);
    }
  }

  /** Reads the JSON form back. */
  public static HoldPayload parse(String json) {
    try {
      return JSON.readValue(json, HoldPayload.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Stored hold payload is unreadable", e);
    }
  }
}
