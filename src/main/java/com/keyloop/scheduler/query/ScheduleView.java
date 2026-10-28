package com.keyloop.scheduler.query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The dispatcher's Gantt board for one dealership-local day (spec 14.5). {@code openingHours} is
 * null on a closed day. An item's {@code laneId} is null when it has no lane in this grouping (a
 * technician block on the bay board, a job on hold on the technician board).
 */
public record ScheduleView(
    LocalDate date,
    String timeZone,
    GroupBy groupBy,
    OpeningWindow openingHours,
    List<Lane> lanes,
    List<TechnicianLine> technicians,
    List<Item> items) {

  /** Local opening and closing time, e.g. "08:00". */
  public record OpeningWindow(String opens, String closes) {}

  /** One lane: a bay or a technician. */
  public record Lane(UUID id, String type, String name) {}

  /** A technician and their skills, for the dispatcher's qualification hints. */
  public record TechnicianLine(UUID id, String displayName, List<String> skills) {}

  /** A bar on the board. */
  public sealed interface Item permits AppointmentItem, BlockItem {

    /** {@code APPOINTMENT} or {@code BLOCK}. */
    String kind();
  }

  /**
   * An appointment bar; ON_HOLD jobs carry {@code holdReason} and {@code remainingMinutes} and no
   * technician; {@code overrun} marks an IN_PROGRESS job past its end.
   */
  public record AppointmentItem(
      String kind,
      UUID id,
      long version,
      String status,
      String holdReason,
      UUID laneId,
      UUID serviceBayId,
      UUID technicianId,
      Instant start,
      Instant end,
      Integer remainingMinutes,
      String vehicle,
      List<String> services,
      boolean overrun)
      implements Item {}

  /** A resource block bar. */
  public record BlockItem(
      String kind,
      UUID id,
      UUID laneId,
      String resourceType,
      UUID resourceId,
      Instant start,
      Instant end,
      String reason)
      implements Item {}
}
