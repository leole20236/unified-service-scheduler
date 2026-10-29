package com.keyloop.scheduler.booking.domain;

import com.keyloop.scheduler.common.time.TimeRange;
import java.util.Set;
import java.util.UUID;

/**
 * What one conditional status UPDATE does (spec 6.2, 8.4): allowed source statuses, the target, and
 * the column effects of the action.
 *
 * @param requireNoShowGrace only when DB {@code now() >= start_at + 30 minutes}
 * @param startNow {@code started_at = now()} (START)
 * @param addStint add the current stint to {@code worked_minutes} (HOLD, COMPLETE)
 * @param endNowIfEarlier end the appointment at {@code now()} if that is before its end (COMPLETE)
 * @param holdReason set while ON_HOLD (HOLD)
 * @param resume new resources and remaining period (RESUME), else null
 */
public record StatusChange(
    Set<AppointmentStatus> from,
    AppointmentStatus to,
    boolean requireNoShowGrace,
    boolean startNow,
    boolean addStint,
    boolean endNowIfEarlier,
    HoldReason holdReason,
    Resume resume) {

  /** Where and when a resumed job continues. */
  public record Resume(UUID serviceBayId, UUID technicianId, TimeRange period) {}
}
