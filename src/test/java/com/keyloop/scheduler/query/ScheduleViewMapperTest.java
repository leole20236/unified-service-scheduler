package com.keyloop.scheduler.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.query.ScheduleView.AppointmentItem;
import com.keyloop.scheduler.query.ScheduleView.BlockItem;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScheduleViewMapperTest {

  /** Riverside, Wednesday 4 Nov 2026 (London = UTC in November). */
  private static final LocalDate WEDNESDAY = LocalDate.of(2026, 11, 4);

  private static final Instant NINE = Instant.parse("2026-11-04T09:00:00Z");
  private static final Instant TEN = Instant.parse("2026-11-04T10:00:00Z");
  private static final UUID APPOINTMENT = UUID.fromString("0192f0b9-0000-7000-8000-0000000000a1");

  private static ResourceCatalog catalog() {
    return new ResourceCatalog(
        CatalogFixtures.riverside(),
        List.of(CatalogFixtures.OIL_CHANGE),
        List.of(
            new BayView(Fixtures.RIVERSIDE_BAY_2, "Bay 2"),
            new BayView(Fixtures.RIVERSIDE_BAY_1, "Bay 1")),
        List.of(
            new TechnicianView(Fixtures.SAM, "Sam", Set.of(Fixtures.OIL, Fixtures.BRAKES)),
            new TechnicianView(Fixtures.ANA, "Ana", Set.of(Fixtures.DIAG))));
  }

  private static ScheduleAppointmentRow row(String status, Instant start, Instant end) {
    return new ScheduleAppointmentRow(
        APPOINTMENT,
        2,
        status,
        "ON_HOLD".equals(status) ? "PARTS" : null,
        Fixtures.RIVERSIDE_BAY_1,
        Fixtures.SAM,
        start,
        end,
        120,
        40,
        "AB12 CDE",
        "Ford",
        "Focus",
        List.of("OIL_CHANGE", "BRAKE_INSPECTION"));
  }

  private static AppointmentItem only(ScheduleView view) {
    return (AppointmentItem) view.items().get(0);
  }

  private static ScheduleView map(GroupBy groupBy, ScheduleAppointmentRow row, Instant now) {
    return ScheduleViewMapper.toView(catalog(), WEDNESDAY, groupBy, List.of(row), List.of(), now);
  }

  @ParameterizedTest(name = "{0} at {1} => overrun {2}")
  @CsvSource({
    "IN_PROGRESS, 2026-11-04T10:00:01Z, true",
    "IN_PROGRESS, 2026-11-04T10:00:00Z, false",
    "IN_PROGRESS, 2026-11-04T09:30:00Z, false",
    "CHECKED_IN, 2026-11-04T11:00:00Z, false",
    "BOOKED, 2026-11-04T11:00:00Z, false",
    "ON_HOLD, 2026-11-04T11:00:00Z, false",
    "COMPLETED, 2026-11-04T11:00:00Z, false"
  })
  void overrunOnlyForInProgressPastItsEnd(String status, Instant now, boolean overrun) {
    assertThat(only(map(GroupBy.BAY, row(status, NINE, TEN), now)).overrun()).isEqualTo(overrun);
  }

  @Test
  void onHoldItemsExposeReasonAndRemainingMinutesButNoTechnician() {
    AppointmentItem item = only(map(GroupBy.BAY, row("ON_HOLD", NINE, TEN), NINE));

    assertThat(item.kind()).isEqualTo("APPOINTMENT");
    assertThat(item.holdReason()).isEqualTo("PARTS");
    assertThat(item.remainingMinutes()).isEqualTo(80);
    assertThat(item.technicianId()).isNull();
    assertThat(item.laneId()).isEqualTo(Fixtures.RIVERSIDE_BAY_1);
    assertThat(item.serviceBayId()).isEqualTo(Fixtures.RIVERSIDE_BAY_1);
  }

  @Test
  void remainingMinutesNeverDropBelowFive() {
    ScheduleAppointmentRow worked =
        new ScheduleAppointmentRow(
            APPOINTMENT,
            2,
            "ON_HOLD",
            "CUSTOMER_APPROVAL",
            Fixtures.RIVERSIDE_BAY_1,
            Fixtures.SAM,
            NINE,
            TEN,
            45,
            50,
            null,
            null,
            null,
            List.of());

    AppointmentItem item = only(map(GroupBy.BAY, worked, NINE));

    assertThat(item.remainingMinutes()).isEqualTo(5);
    assertThat(item.vehicle()).isEmpty();
  }

  @Test
  void activeItemsCarryTheirTechnicianAndNoHoldFields() {
    AppointmentItem item = only(map(GroupBy.BAY, row("BOOKED", NINE, TEN), NINE));

    assertThat(item.technicianId()).isEqualTo(Fixtures.SAM);
    assertThat(item.holdReason()).isNull();
    assertThat(item.remainingMinutes()).isNull();
    assertThat(item.vehicle()).isEqualTo("AB12 CDE · Ford Focus");
    assertThat(item.services()).containsExactly("OIL_CHANGE", "BRAKE_INSPECTION");
    assertThat(item.version()).isEqualTo(2);
    assertThat(item.start()).isEqualTo(NINE);
    assertThat(item.end()).isEqualTo(TEN);
  }

  @Test
  void bayGroupingHasBayLanesSortedByNameAndTechniciansWithSkills() {
    ScheduleView view = map(GroupBy.BAY, row("BOOKED", NINE, TEN), NINE);

    assertThat(view.date()).isEqualTo(WEDNESDAY);
    assertThat(view.timeZone()).isEqualTo("Europe/London");
    assertThat(view.groupBy()).isEqualTo(GroupBy.BAY);
    assertThat(view.openingHours()).isEqualTo(new ScheduleView.OpeningWindow("08:00", "18:00"));
    assertThat(view.lanes())
        .containsExactly(
            new ScheduleView.Lane(Fixtures.RIVERSIDE_BAY_1, "BAY", "Bay 1"),
            new ScheduleView.Lane(Fixtures.RIVERSIDE_BAY_2, "BAY", "Bay 2"));
    assertThat(view.technicians())
        .containsExactly(
            new ScheduleView.TechnicianLine(Fixtures.ANA, "Ana", List.of("DIAG")),
            new ScheduleView.TechnicianLine(Fixtures.SAM, "Sam", List.of("BRAKES", "OIL")));
  }

  @Test
  void technicianGroupingUsesTechnicianLanesAndOnHoldJobsLeaveTheirLane() {
    ScheduleView active = map(GroupBy.TECHNICIAN, row("IN_PROGRESS", NINE, TEN), NINE);
    ScheduleView held = map(GroupBy.TECHNICIAN, row("ON_HOLD", NINE, TEN), NINE);

    assertThat(active.lanes())
        .containsExactly(
            new ScheduleView.Lane(Fixtures.ANA, "TECHNICIAN", "Ana"),
            new ScheduleView.Lane(Fixtures.SAM, "TECHNICIAN", "Sam"));
    assertThat(only(active).laneId()).isEqualTo(Fixtures.SAM);
    assertThat(only(held).laneId()).isNull();
  }

  @Test
  void blocksSitOnTheirResourceLaneWhenTheGroupingMatches() {
    UUID bayBlock = UUID.randomUUID();
    UUID technicianBlock = UUID.randomUUID();
    List<ScheduleBlockRow> blocks =
        List.of(
            new ScheduleBlockRow(bayBlock, Fixtures.RIVERSIDE_BAY_2, null, NINE, TEN, "Lift"),
            new ScheduleBlockRow(technicianBlock, null, Fixtures.SAM, NINE, TEN, "Training"));

    ScheduleView byBay =
        ScheduleViewMapper.toView(catalog(), WEDNESDAY, GroupBy.BAY, List.of(), blocks, NINE);
    ScheduleView byTechnician =
        ScheduleViewMapper.toView(
            catalog(), WEDNESDAY, GroupBy.TECHNICIAN, List.of(), blocks, NINE);

    BlockItem bay = (BlockItem) byBay.items().get(0);
    assertThat(bay.kind()).isEqualTo("BLOCK");
    assertThat(bay.laneId()).isEqualTo(Fixtures.RIVERSIDE_BAY_2);
    assertThat(bay.resourceType()).isEqualTo("BAY");
    assertThat(bay.reason()).isEqualTo("Lift");
    assertThat(((BlockItem) byBay.items().get(1)).laneId()).isNull();
    assertThat(((BlockItem) byTechnician.items().get(0)).laneId()).isNull();
    BlockItem training = (BlockItem) byTechnician.items().get(1);
    assertThat(training.laneId()).isEqualTo(Fixtures.SAM);
    assertThat(training.resourceType()).isEqualTo("TECHNICIAN");
    assertThat(training.resourceId()).isEqualTo(Fixtures.SAM);
  }

  @Test
  void closedDayHasNoOpeningHours() {
    ScheduleView sunday =
        ScheduleViewMapper.toView(
            catalog(), LocalDate.of(2026, 11, 8), GroupBy.BAY, List.of(), List.of(), NINE);

    assertThat(sunday.openingHours()).isNull();
    assertThat(sunday.items()).isEmpty();
  }
}
