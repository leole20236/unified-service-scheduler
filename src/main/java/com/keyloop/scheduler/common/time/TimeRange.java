package com.keyloop.scheduler.common.time;

import java.time.Duration;
import java.time.Instant;

/** A half-open instant range {@code [start, end)} with {@code start < end}. */
public record TimeRange(Instant start, Instant end) {

  /** Rejects empty and inverted ranges. */
  public TimeRange {
    if (!start.isBefore(end)) {
      throw new IllegalArgumentException("start must be before end: " + start + " / " + end);
    }
  }

  /** Whether the two half-open ranges share any instant. */
  public boolean overlaps(TimeRange other) {
    return start.isBefore(other.end) && other.start.isBefore(end);
  }

  /** A copy whose end is moved later by the given duration. */
  public TimeRange extendEnd(Duration extra) {
    return new TimeRange(start, end.plus(extra));
  }

  /** Length in whole minutes. */
  public long minutes() {
    return Duration.between(start, end).toMinutes();
  }
}
