package com.keyloop.scheduler.common.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Problem SLOT_UNAVAILABLE with alternative start times (UTC) and, for staff only, the ids of the
 * conflicting appointments. A {@code null} conflicts list omits the property entirely.
 */
public class SlotUnavailableException extends ApiException {

  private final List<Instant> alternatives;
  private final List<UUID> conflicts;

  /** Creates the problem with its human-readable detail. */
  public SlotUnavailableException(String detail, List<Instant> alternatives, List<UUID> conflicts) {
    super(ProblemCodes.SLOT_UNAVAILABLE, detail);
    this.alternatives = List.copyOf(alternatives);
    this.conflicts = conflicts == null ? null : List.copyOf(conflicts);
  }

  @Override
  public Map<String, Object> extras() {
    Map<String, Object> extras = new LinkedHashMap<>();
    extras.put("alternatives", alternatives);
    if (conflicts != null) {
      extras.put("conflicts", conflicts);
    }
    return extras;
  }
}
