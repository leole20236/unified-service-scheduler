package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.common.time.TimeRange;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Active reservations per resource (bay or technician) over some window; immutable. */
public record Occupancy(Map<UUID, List<TimeRange>> byResource) {

  private static final Occupancy EMPTY = new Occupancy(Map.of());

  /** Defensive copy so the record is truly immutable. */
  public Occupancy {
    Map<UUID, List<TimeRange>> copy = new HashMap<>();
    byResource.forEach((id, ranges) -> copy.put(id, List.copyOf(ranges)));
    byResource = Map.copyOf(copy);
  }

  /** No reservations at all. */
  public static Occupancy empty() {
    return EMPTY;
  }

  /** Whether the resource has no reservation overlapping the range. */
  public boolean isFree(UUID resourceId, TimeRange r) {
    return byResource.getOrDefault(resourceId, List.of()).stream().noneMatch(r::overlaps);
  }

  /** Total reserved minutes of the resource. */
  public long bookedMinutes(UUID resourceId) {
    return byResource.getOrDefault(resourceId, List.of()).stream()
        .mapToLong(TimeRange::minutes)
        .sum();
  }

  /** A copy with one more reservation. */
  public Occupancy plus(UUID resourceId, TimeRange r) {
    Map<UUID, List<TimeRange>> copy = new HashMap<>(byResource);
    List<TimeRange> ranges = new ArrayList<>(copy.getOrDefault(resourceId, List.of()));
    ranges.add(r);
    copy.put(resourceId, ranges);
    return new Occupancy(copy);
  }

  /** A copy without one reservation equal to {@code r} (the caller's own, when it moves). */
  public Occupancy minus(UUID resourceId, TimeRange r) {
    Map<UUID, List<TimeRange>> copy = new HashMap<>(byResource);
    List<TimeRange> ranges = new ArrayList<>(copy.getOrDefault(resourceId, List.of()));
    ranges.remove(r);
    copy.put(resourceId, ranges);
    return new Occupancy(copy);
  }
}
