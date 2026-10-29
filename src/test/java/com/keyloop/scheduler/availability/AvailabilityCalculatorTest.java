package com.keyloop.scheduler.availability;

import static org.assertj.core.api.Assertions.assertThat;

import com.keyloop.scheduler.booking.domain.JobPlan;
import com.keyloop.scheduler.booking.domain.JobPlanner;
import com.keyloop.scheduler.booking.domain.Occupancy;
import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AvailabilityCalculatorTest {

  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");
  private static final Instant FAR_AHEAD = Instant.parse("2030-01-01T00:00:00Z");

  private static ResourceCatalog riverside() {
    return new ResourceCatalog(
        CatalogFixtures.riverside(),
        List.of(CatalogFixtures.OIL_CHANGE, CatalogFixtures.BRAKE_INSPECTION),
        List.of(new BayView(Fixtures.RIVERSIDE_BAY_1, "Bay 1")),
        List.of(new TechnicianView(Fixtures.SAM, "Sam", Set.of("OIL", "BRAKES"))));
  }

  private static ResourceCatalog solo() {
    return new ResourceCatalog(
        CatalogFixtures.riverside(),
        List.of(CatalogFixtures.OIL_CHANGE),
        List.of(new BayView(Fixtures.SOLO_BAY, "Bay")),
        List.of(new TechnicianView(Fixtures.SOLO_TECH, "Solo", Set.of("OIL", "BRAKES"))));
  }

  private static TimeRange london(LocalDate date, int h1, int m1, int h2, int m2) {
    DealershipView d = CatalogFixtures.riverside();
    return new TimeRange(
        date.atTime(LocalTime.of(h1, m1)).atZone(d.zoneId()).toInstant(),
        date.atTime(LocalTime.of(h2, m2)).atZone(d.zoneId()).toInstant());
  }

  private static List<LocalTime> localTimes(List<OffsetDateTime> starts) {
    return starts.stream().map(OffsetDateTime::toLocalTime).toList();
  }

  @Test
  void emptyDayOffersEveryQuarterHourThatFits() {
    List<OffsetDateTime> starts =
        AvailabilityCalculator.starts(
            riverside(), Set.of("OIL"), 120, MONDAY, Occupancy.empty(), LONG_AGO);

    assertThat(starts).hasSize(33);
    assertThat(starts.get(0).toLocalTime()).isEqualTo(LocalTime.of(8, 0));
    assertThat(starts.get(32).toLocalTime()).isEqualTo(LocalTime.of(16, 0));
  }

  @Test
  void closedDayHasNoStarts() {
    assertThat(
            AvailabilityCalculator.starts(
                riverside(),
                Set.of("OIL"),
                60,
                LocalDate.of(2026, 11, 1),
                Occupancy.empty(),
                LONG_AGO))
        .isEmpty();
  }

  @Test
  void fullyBookedSoloDayOffersNothing() {
    Occupancy booked =
        Occupancy.empty()
            .plus(Fixtures.SOLO_TECH, london(MONDAY, 8, 0, 18, 0))
            .plus(Fixtures.SOLO_BAY, london(MONDAY, 8, 0, 18, 0));

    assertThat(AvailabilityCalculator.starts(solo(), Set.of("OIL"), 45, MONDAY, booked, LONG_AGO))
        .isEmpty();
  }

  @Test
  void slotFreedWhenReservationInactive() {
    TimeRange job = london(MONDAY, 9, 0, 10, 0);
    Occupancy booked = Occupancy.empty().plus(Fixtures.SOLO_TECH, job).plus(Fixtures.SOLO_BAY, job);
    List<OffsetDateTime> whileBooked =
        AvailabilityCalculator.starts(solo(), Set.of("OIL"), 60, MONDAY, booked, LONG_AGO);
    // an inactive reservation is never loaded, so the occupancy is simply empty again
    List<OffsetDateTime> afterCancel =
        AvailabilityCalculator.starts(
            solo(), Set.of("OIL"), 60, MONDAY, Occupancy.empty(), LONG_AGO);

    assertThat(localTimes(whileBooked)).doesNotContain(LocalTime.of(9, 0), LocalTime.of(9, 45));
    assertThat(localTimes(whileBooked)).contains(LocalTime.of(8, 0), LocalTime.of(10, 0));
    assertThat(localTimes(afterCancel)).contains(LocalTime.of(9, 0));
  }

  @Test
  void earliestCutsMorning() {
    Instant earliest = london(MONDAY, 10, 7, 11, 0).start();

    List<OffsetDateTime> starts =
        AvailabilityCalculator.starts(
            riverside(), Set.of("OIL"), 60, MONDAY, Occupancy.empty(), earliest);

    assertThat(starts.get(0).toLocalTime()).isEqualTo(LocalTime.of(10, 15));
  }

  @Test
  void unqualifiedSkillsOfferNothing() {
    assertThat(
            AvailabilityCalculator.starts(
                riverside(), Set.of("DIAG"), 60, MONDAY, Occupancy.empty(), LONG_AGO))
        .isEmpty();
  }

  @Test
  void springForwardGapNeverOffered() {
    // London 2026-03-29: 01:00 GMT jumps to 02:00 BST; open 00:30-03:30 local
    DealershipView d = CatalogFixtures.nightOwl();
    ResourceCatalog c =
        new ResourceCatalog(
            d,
            List.of(CatalogFixtures.OIL_CHANGE),
            List.of(new BayView(Fixtures.SOLO_BAY, "Bay")),
            List.of(new TechnicianView(Fixtures.SOLO_TECH, "Solo", Set.of("OIL"))));

    List<OffsetDateTime> starts =
        AvailabilityCalculator.starts(
            c, Set.of("OIL"), 30, LocalDate.of(2026, 3, 29), Occupancy.empty(), LONG_AGO);

    assertThat(starts).isNotEmpty();
    assertThat(localTimes(starts))
        .noneMatch(t -> !t.isBefore(LocalTime.of(1, 0)) && t.isBefore(LocalTime.of(2, 0)));
    // 00:30Z..02:30Z is two real hours; 30 minute jobs: 00:30 .. 02:00Z = 7 starts
    assertThat(starts).hasSize(7);
    assertThat(starts.get(starts.size() - 1).toInstant())
        .isEqualTo(Instant.parse("2026-03-29T02:00:00Z"));
  }

  @Test
  void alternativesAreNearestFiveAfterRequested() {
    ResourceCatalog c = riverside();
    OffsetDateTime requested = OffsetDateTime.parse("2026-11-02T09:00:00Z");
    JobPlan plan = JobPlanner.plan(c, List.of(Fixtures.OIL_CHANGE), requested);
    Occupancy booked = Occupancy.empty().plus(Fixtures.SAM, london(MONDAY, 9, 0, 9, 45));

    List<OffsetDateTime> alternatives =
        AvailabilityCalculator.alternatives(c, plan, d -> booked, LONG_AGO, FAR_AHEAD, 5);

    assertThat(localTimes(alternatives))
        .containsExactly(
            LocalTime.of(9, 45),
            LocalTime.of(10, 0),
            LocalTime.of(10, 15),
            LocalTime.of(10, 30),
            LocalTime.of(10, 45));
  }

  @Test
  void alternativesRollIntoFollowingDaysAndStopAtLimit() {
    ResourceCatalog c = riverside();
    OffsetDateTime requested = OffsetDateTime.parse("2026-11-02T16:45:00Z");
    JobPlan plan = JobPlanner.plan(c, List.of(Fixtures.OIL_CHANGE), requested);

    List<OffsetDateTime> alternatives =
        AvailabilityCalculator.alternatives(
            c, plan, d -> Occupancy.empty(), LONG_AGO, FAR_AHEAD, 3);

    assertThat(alternatives).hasSize(3);
    assertThat(alternatives.get(0).toInstant()).isEqualTo(Instant.parse("2026-11-02T17:00:00Z"));
    assertThat(alternatives).isSortedAccordingTo(java.util.Comparator.naturalOrder());
  }

  @Test
  void alternativesSkipFullyBookedNextDayAndLoadEachDateLazily() {
    // Solo catalog in London; requested Monday 17:00 (last start of the day for 45 min is 17:15)
    ResourceCatalog c = solo();
    OffsetDateTime requested = OffsetDateTime.parse("2026-11-02T17:15:00Z");
    JobPlan plan = JobPlanner.plan(c, List.of(Fixtures.OIL_CHANGE), requested);
    LocalDate tuesday = MONDAY.plusDays(1);
    Occupancy tuesdayFull =
        Occupancy.empty()
            .plus(Fixtures.SOLO_TECH, london(tuesday, 8, 0, 18, 0))
            .plus(Fixtures.SOLO_BAY, london(tuesday, 8, 0, 18, 0));
    List<LocalDate> loaded = new java.util.ArrayList<>();

    List<OffsetDateTime> alternatives =
        AvailabilityCalculator.alternatives(
            c,
            plan,
            date -> {
              loaded.add(date);
              return date.equals(tuesday) ? tuesdayFull : Occupancy.empty();
            },
            LONG_AGO,
            FAR_AHEAD,
            2);

    // Monday has nothing after 17:15, Tuesday is full, so both come from Wednesday morning
    LocalDate wednesday = MONDAY.plusDays(2);
    assertThat(alternatives)
        .extracting(OffsetDateTime::toInstant)
        .containsExactly(
            london(wednesday, 8, 0, 9, 0).start(), london(wednesday, 8, 15, 9, 0).start());
    assertThat(loaded).containsExactly(MONDAY, tuesday, wednesday);
  }

  @Test
  void alternativesNeverLoadClosedDays() {
    // Riverside is closed on Sunday 8 Nov
    ResourceCatalog c = riverside();
    OffsetDateTime requested = OffsetDateTime.parse("2026-11-07T17:00:00Z");
    JobPlan plan = JobPlanner.plan(c, List.of(Fixtures.OIL_CHANGE), requested);
    List<LocalDate> loaded = new java.util.ArrayList<>();

    List<OffsetDateTime> alternatives =
        AvailabilityCalculator.alternatives(
            c,
            plan,
            date -> {
              loaded.add(date);
              return Occupancy.empty();
            },
            LONG_AGO,
            FAR_AHEAD,
            1);

    assertThat(alternatives.get(0).toInstant()).isEqualTo(Instant.parse("2026-11-07T17:15:00Z"));
    assertThat(loaded).containsExactly(LocalDate.of(2026, 11, 7));
    loaded.clear();
    JobPlan late =
        JobPlanner.plan(
            c, List.of(Fixtures.OIL_CHANGE), OffsetDateTime.parse("2026-11-07T17:15:00Z"));
    AvailabilityCalculator.alternatives(
        c,
        late,
        date -> {
          loaded.add(date);
          return Occupancy.empty();
        },
        LONG_AGO,
        FAR_AHEAD,
        1);
    assertThat(loaded).containsExactly(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 9));
  }

  @Test
  void alternativesStopAtLatestStart() {
    ResourceCatalog c = riverside();
    OffsetDateTime requested = OffsetDateTime.parse("2026-11-02T09:00:00Z");
    JobPlan plan = JobPlanner.plan(c, List.of(Fixtures.OIL_CHANGE), requested);
    Instant latest = Instant.parse("2026-11-02T09:30:00Z");

    List<OffsetDateTime> alternatives =
        AvailabilityCalculator.alternatives(c, plan, d -> Occupancy.empty(), LONG_AGO, latest, 5);

    assertThat(alternatives)
        .extracting(OffsetDateTime::toInstant)
        .containsExactly(
            Instant.parse("2026-11-02T09:15:00Z"), Instant.parse("2026-11-02T09:30:00Z"));
  }
}
