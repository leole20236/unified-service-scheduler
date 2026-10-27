package com.keyloop.scheduler.common.api;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Problem SLOT_UNAVAILABLE with alternative start times and (for staff) conflicting ids. */
public class SlotUnavailableException extends ApiException {

  private final List<OffsetDateTime> alternatives;
  private final List<UUID> conflicts;

  /** Creates the problem with its human-readable detail. */
  public SlotUnavailableException(
      String detail, List<OffsetDateTime> alternatives, List<UUID> conflicts) {
    super(ProblemCodes.SLOT_UNAVAILABLE, detail);
    this.alternatives = alternatives;
    this.conflicts = conflicts;
  }

  @Override
  public Map<String, Object> extras() {
    Map<String, Object> extras = new LinkedHashMap<>();
    extras.put("alternatives", alternatives);
    extras.put("conflicts", conflicts);
    return extras;
  }
}
