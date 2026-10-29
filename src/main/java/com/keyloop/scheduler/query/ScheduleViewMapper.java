package com.keyloop.scheduler.query;

import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.OpeningHours;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.query.ScheduleView.AppointmentItem;
import com.keyloop.scheduler.query.ScheduleView.BlockItem;
import com.keyloop.scheduler.query.ScheduleView.Item;
import com.keyloop.scheduler.query.ScheduleView.Lane;
import com.keyloop.scheduler.query.ScheduleView.OpeningWindow;
import com.keyloop.scheduler.query.ScheduleView.TechnicianLine;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Builds the Gantt board from the catalog and the day's rows (spec 14.5). */
public final class ScheduleViewMapper {

  /** A resumed job is never planned shorter than this (spec 6.2). */
  static final int MIN_REMAINING_MINUTES = 5;

  private static final String ON_HOLD = "ON_HOLD";
  private static final String IN_PROGRESS = "IN_PROGRESS";
  private static final String COMPLETED = "COMPLETED";
  private static final String BAY = "BAY";
  private static final String TECHNICIAN = "TECHNICIAN";
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

  private ScheduleViewMapper() {}

  /** The board; {@code now} (database time) decides {@code overrun}. */
  public static ScheduleView toView(
      ResourceCatalog catalog,
      LocalDate date,
      GroupBy groupBy,
      List<ScheduleAppointmentRow> appointments,
      List<ScheduleBlockRow> blocks,
      Instant now) {
    DealershipView d = catalog.dealership();
    OpeningHours hours = d.openingHours().get(date.getDayOfWeek());
    List<Lane> lanes =
        groupBy == GroupBy.BAY
            ? catalog.bays().stream()
                .map(b -> new Lane(b.id(), BAY, b.name()))
                .sorted(Comparator.comparing(Lane::name))
                .toList()
            : catalog.technicians().stream()
                .map(t -> new Lane(t.id(), TECHNICIAN, t.displayName()))
                .sorted(Comparator.comparing(Lane::name))
                .toList();
    List<TechnicianLine> technicians =
        catalog.technicians().stream()
            .map(
                t ->
                    new TechnicianLine(
                        t.id(), t.displayName(), t.skills().stream().sorted().toList()))
            .sorted(Comparator.comparing(TechnicianLine::displayName))
            .toList();
    TimeRange day =
        new TimeRange(
            date.atStartOfDay(d.zoneId()).toInstant(),
            date.plusDays(1).atStartOfDay(d.zoneId()).toInstant());
    Duration buffer = Duration.ofMinutes(d.bayBufferMinutes());
    List<Item> items = new ArrayList<>();
    for (ScheduleAppointmentRow a : appointments) {
      TimeRange bar = bar(a, buffer);
      if (bar.overlaps(day)) {
        items.add(appointment(a, bar, groupBy, now));
      }
    }
    blocks.forEach(b -> items.add(block(b, groupBy)));
    return new ScheduleView(
        date,
        d.zoneId().getId(),
        groupBy,
        hours == null
            ? null
            : new OpeningWindow(CLOCK.format(hours.opensAt()), CLOCK.format(hours.closesAt())),
        lanes,
        technicians,
        items);
  }

  /**
   * Where the job is drawn: its current segment. A job in progress (or completed) from its
   * technician reservation's start to its end; a job on hold over its kept bay (without the
   * buffer), or up to the hold time when the bay was released; otherwise its planned period.
   * Segments worked before a hold are not kept (spec 6.2), so they are not drawn.
   */
  static TimeRange bar(ScheduleAppointmentRow a, Duration buffer) {
    TimeRange planned = new TimeRange(a.start(), a.end());
    switch (a.status()) {
      case IN_PROGRESS, COMPLETED -> {
        if (a.technicianFrom() != null && a.technicianFrom().isBefore(a.end())) {
          return new TimeRange(a.technicianFrom(), a.end());
        }
        return planned;
      }
      case ON_HOLD -> {
        boolean bayKept =
            a.bayTo() != null && (a.technicianTo() == null || a.bayTo().isAfter(a.technicianTo()));
        if (bayKept) {
          Instant end = a.bayTo().minus(buffer);
          return new TimeRange(a.bayFrom(), end.isAfter(a.bayFrom()) ? end : a.bayTo());
        }
        if (a.technicianTo() != null) {
          return new TimeRange(a.technicianFrom(), a.technicianTo());
        }
        return planned;
      }
      default -> {
        return planned;
      }
    }
  }

  private static AppointmentItem appointment(
      ScheduleAppointmentRow a, TimeRange bar, GroupBy groupBy, Instant now) {
    boolean onHold = ON_HOLD.equals(a.status());
    UUID technicianId = onHold ? null : a.technicianId();
    UUID laneId = groupBy == GroupBy.BAY ? a.serviceBayId() : technicianId;
    return new AppointmentItem(
        "APPOINTMENT",
        a.id(),
        a.version(),
        a.status(),
        onHold ? a.holdReason() : null,
        laneId,
        a.serviceBayId(),
        technicianId,
        bar.start(),
        bar.end(),
        onHold ? Math.max(MIN_REMAINING_MINUTES, a.plannedMinutes() - a.workedMinutes()) : null,
        vehicle(a),
        a.serviceCodes(),
        IN_PROGRESS.equals(a.status()) && now.isAfter(a.end()));
  }

  private static BlockItem block(ScheduleBlockRow b, GroupBy groupBy) {
    boolean bay = b.serviceBayId() != null;
    String type = bay ? BAY : TECHNICIAN;
    UUID resource = bay ? b.serviceBayId() : b.technicianId();
    return new BlockItem(
        "BLOCK",
        b.id(),
        groupBy.name().equals(type) ? resource : null,
        type,
        resource,
        b.start(),
        b.end(),
        b.reason());
  }

  /** e.g. "AB12 CDE · Ford Focus"; missing parts are left out. */
  private static String vehicle(ScheduleAppointmentRow a) {
    String model =
        Stream.of(a.make(), a.model()).filter(Objects::nonNull).collect(Collectors.joining(" "));
    return Stream.of(a.registration(), model)
        .filter(s -> s != null && !s.isBlank())
        .collect(Collectors.joining(" · "));
  }
}
