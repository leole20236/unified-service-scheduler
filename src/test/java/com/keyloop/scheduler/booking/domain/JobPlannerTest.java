package com.keyloop.scheduler.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keyloop.scheduler.common.api.BusinessRuleException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JobPlannerTest {

  private static final OffsetDateTime START = OffsetDateTime.parse("2026-11-02T09:00:00Z");

  @Test
  void sumsDurationsAndSkills() {
    var catalog = CatalogFixtures.catalog(CatalogFixtures.riverside());
    JobPlan plan =
        JobPlanner.plan(catalog, List.of(Fixtures.OIL_CHANGE, Fixtures.BRAKE_INSPECTION), START);
    assertThat(plan.durationMinutes()).isEqualTo(120);
    assertThat(plan.requiredSkills()).isEqualTo(Set.of(Fixtures.OIL, Fixtures.BRAKES));
    assertThat(plan.services()).hasSize(2);
    assertThat(plan.period().minutes()).isEqualTo(120);
  }

  @Test
  void bayPeriodAddsBuffer() {
    var catalog = CatalogFixtures.catalog(CatalogFixtures.hudson());
    JobPlan plan = JobPlanner.plan(catalog, List.of(Fixtures.OIL_CHANGE), START);
    assertThat(plan.bayPeriod().minutes()).isEqualTo(plan.period().minutes() + 10);
    assertThat(plan.bayPeriod().start()).isEqualTo(plan.period().start());
  }

  @Test
  void foreignOffsetIsSameInstant() {
    var catalog = CatalogFixtures.catalog(CatalogFixtures.riverside());
    JobPlan plan =
        JobPlanner.plan(
            catalog,
            List.of(Fixtures.OIL_CHANGE),
            OffsetDateTime.parse("2026-11-02T16:00:00+07:00"));
    assertThat(plan.period().start()).isEqualTo(Instant.parse("2026-11-02T09:00:00Z"));
    assertThat(plan.localStart()).isEqualTo(LocalDateTime.parse("2026-11-02T09:00"));
    assertThat(plan.localDate()).isEqualTo(plan.localStart().toLocalDate());
  }

  @Test
  void duplicateServicesRejected() {
    var catalog = CatalogFixtures.catalog(CatalogFixtures.riverside());
    assertThatThrownBy(
            () ->
                JobPlanner.plan(catalog, List.of(Fixtures.OIL_CHANGE, Fixtures.OIL_CHANGE), START))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void emptyServicesRejected() {
    var catalog = CatalogFixtures.catalog(CatalogFixtures.riverside());
    assertThatThrownBy(() -> JobPlanner.plan(catalog, List.of(), START))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void sixServicesRejected() {
    var catalog = CatalogFixtures.catalog(CatalogFixtures.riverside());
    List<UUID> six =
        List.of(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID());
    assertThatThrownBy(() -> JobPlanner.plan(catalog, six, START))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void serviceNotOfferedRejected() {
    var catalog = CatalogFixtures.catalog(CatalogFixtures.riverside());
    assertThatThrownBy(() -> JobPlanner.plan(catalog, List.of(UUID.randomUUID()), START))
        .isInstanceOfSatisfying(
            BusinessRuleException.class,
            e -> assertThat(e.code()).isEqualTo(ProblemCodes.SERVICE_NOT_OFFERED));
  }
}
