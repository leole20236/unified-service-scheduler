package com.keyloop.scheduler.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.api.BusinessRuleException;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AllocationServiceTest {

  private static final OffsetDateTime START = OffsetDateTime.parse("2026-11-02T09:00:00Z");

  private final AllocationService service = new AllocationService(new SplittableRandom(42));

  private static ResourceCatalog riverside() {
    return new ResourceCatalog(
        CatalogFixtures.riverside(),
        List.of(CatalogFixtures.OIL_CHANGE, CatalogFixtures.BRAKE_INSPECTION),
        List.of(
            new BayView(Fixtures.RIVERSIDE_BAY_1, "Bay 1"),
            new BayView(Fixtures.RIVERSIDE_BAY_2, "Bay 2"),
            new BayView(Fixtures.RIVERSIDE_BAY_3, "Bay 3")),
        List.of(
            new TechnicianView(Fixtures.SAM, "Sam", Set.of("OIL", "BRAKES")),
            new TechnicianView(Fixtures.LEE, "Lee", Set.of("OIL")),
            new TechnicianView(Fixtures.ANA, "Ana", Set.of("OIL", "BRAKES")),
            new TechnicianView(Fixtures.TOM, "Tom", Set.of("OIL", "BRAKES"))));
  }

  private static ResourceCatalog hudson() {
    return new ResourceCatalog(
        CatalogFixtures.hudson(),
        List.of(CatalogFixtures.OIL_CHANGE),
        List.of(new BayView(Fixtures.HUDSON_BAY_1, "Bay 1")),
        List.of(new TechnicianView(Fixtures.JO, "Jo", Set.of("OIL"))));
  }

  private static TimeRange range(int startMinute, int endMinute) {
    Instant base = START.toInstant();
    return new TimeRange(base.plusSeconds(startMinute * 60L), base.plusSeconds(endMinute * 60L));
  }

  @Test
  void onlyQualifiedTechnicians() {
    ResourceCatalog c = riverside();
    JobPlan plan = JobPlanner.plan(c, List.of(Fixtures.BRAKE_INSPECTION), START);

    List<ResourcePair> pairs =
        service.rank(c, plan, Occupancy.empty(), Optional.empty(), Optional.empty());

    assertThat(pairs).hasSize(9);
    assertThat(pairs.stream().map(ResourcePair::technicianId).distinct())
        .containsExactlyInAnyOrder(Fixtures.SAM, Fixtures.ANA, Fixtures.TOM);
  }

  @Test
  void leastLoadedTechnicianFirst() {
    ResourceCatalog c = riverside();
    JobPlan plan = JobPlanner.plan(c, List.of(Fixtures.BRAKE_INSPECTION), START);
    Occupancy occupancy =
        Occupancy.empty()
            .plus(Fixtures.SAM, range(-240, -120))
            .plus(Fixtures.ANA, range(-120, -90));

    List<ResourcePair> pairs = service.rank(c, plan, occupancy, Optional.empty(), Optional.empty());

    List<UUID> order = pairs.stream().map(ResourcePair::technicianId).distinct().toList();
    assertThat(order).containsExactly(Fixtures.TOM, Fixtures.ANA, Fixtures.SAM);
  }

  @Test
  void busyBayExcluded() {
    ResourceCatalog c = riverside();
    JobPlan plan = JobPlanner.plan(c, List.of(Fixtures.OIL_CHANGE), START);
    Occupancy occupancy =
        Occupancy.empty()
            .plus(Fixtures.RIVERSIDE_BAY_2, range(30, 60))
            .plus(Fixtures.SAM, range(0, 45));

    List<ResourcePair> pairs = service.rank(c, plan, occupancy, Optional.empty(), Optional.empty());

    assertThat(pairs).noneMatch(p -> p.bayId().equals(Fixtures.RIVERSIDE_BAY_2));
    assertThat(pairs).noneMatch(p -> p.technicianId().equals(Fixtures.SAM));
    assertThat(pairs).hasSize(6);
  }

  @Test
  void bufferAppliedToBayOnly() {
    ResourceCatalog c = hudson();
    JobPlan plan = JobPlanner.plan(c, List.of(Fixtures.OIL_CHANGE), START);
    // previous job: technician until 09:00 sharp; its bay reservation includes the 10 min buffer
    Occupancy previousEnded =
        Occupancy.empty()
            .plus(Fixtures.JO, range(-45, 0))
            .plus(Fixtures.HUDSON_BAY_1, range(-45, 10));

    assertThat(service.rank(c, plan, previousEnded, Optional.empty(), Optional.empty())).isEmpty();

    // the new job's own buffer blocks a following bay booking, not a following technician
    Occupancy followUp =
        Occupancy.empty()
            .plus(Fixtures.JO, range(45, 90))
            .plus(Fixtures.HUDSON_BAY_1, range(50, 90));
    assertThat(plan.bayPeriod().minutes()).isEqualTo(55);
    assertThat(service.rank(c, plan, followUp, Optional.empty(), Optional.empty())).isEmpty();
    Occupancy techOnly = Occupancy.empty().plus(Fixtures.JO, range(45, 90));
    assertThat(service.rank(c, plan, techOnly, Optional.empty(), Optional.empty())).hasSize(1);
  }

  @Test
  void pinnedUnqualifiedTechnicianRejected() {
    ResourceCatalog c = riverside();
    JobPlan plan = JobPlanner.plan(c, List.of(Fixtures.BRAKE_INSPECTION), START);

    assertThatThrownBy(
            () ->
                service.rank(
                    c, plan, Occupancy.empty(), Optional.empty(), Optional.of(Fixtures.LEE)))
        .isInstanceOf(BusinessRuleException.class)
        .hasMessageContaining("Lee");
  }

  @Test
  void pinnedResourcesNarrowTheChoice() {
    ResourceCatalog c = riverside();
    JobPlan plan = JobPlanner.plan(c, List.of(Fixtures.OIL_CHANGE), START);

    assertThat(
            service.rank(
                c,
                plan,
                Occupancy.empty(),
                Optional.of(Fixtures.RIVERSIDE_BAY_3),
                Optional.of(Fixtures.LEE)))
        .containsExactly(new ResourcePair(Fixtures.RIVERSIDE_BAY_3, Fixtures.LEE));
    assertThat(
            service.rank(
                c,
                plan,
                Occupancy.empty(),
                Optional.of(UUID.randomUUID()),
                Optional.of(Fixtures.LEE)))
        .isEmpty();
    assertThat(
            service.rank(
                c, plan, Occupancy.empty(), Optional.empty(), Optional.of(UUID.randomUUID())))
        .isEmpty();
  }

  @Test
  void occupancyBasics() {
    Occupancy o =
        Occupancy.empty().plus(Fixtures.SAM, range(0, 30)).plus(Fixtures.SAM, range(60, 75));
    assertThat(o.bookedMinutes(Fixtures.SAM)).isEqualTo(45);
    assertThat(o.bookedMinutes(Fixtures.ANA)).isZero();
    assertThat(o.isFree(Fixtures.SAM, range(30, 60))).isTrue();
    assertThat(o.isFree(Fixtures.SAM, range(29, 60))).isFalse();
    assertThat(Occupancy.empty().byResource()).isEmpty();
  }
}
