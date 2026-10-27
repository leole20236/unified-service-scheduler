package com.keyloop.scheduler.hold;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** The hold as the API shows it (spec 14.5): times in UTC. */
public record HoldResource(
    UUID id,
    UUID dealershipId,
    List<UUID> serviceTypeIds,
    OffsetDateTime start,
    OffsetDateTime end,
    OffsetDateTime expiresAt) {

  /** Immutable copy. */
  public HoldResource {
    serviceTypeIds = List.copyOf(serviceTypeIds);
  }
}
