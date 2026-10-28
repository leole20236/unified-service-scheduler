package com.keyloop.scheduler.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScheduleQueryTest {

  private final CatalogService catalogs = mock(CatalogService.class);
  private final ScheduleReadRepository rows = mock(ScheduleReadRepository.class);

  @Test
  void readsTheLocalDayInUtcAndMapsIt() {
    LocalDate summerDay = LocalDate.of(2026, 7, 1);
    when(catalogs.get(Fixtures.RIVERSIDE))
        .thenReturn(CatalogFixtures.catalog(CatalogFixtures.riverside()));
    TimeRange day =
        new TimeRange(Instant.parse("2026-06-30T23:00:00Z"), Instant.parse("2026-07-01T23:00:00Z"));
    when(rows.appointments(Fixtures.RIVERSIDE, day)).thenReturn(List.of());
    when(rows.blocks(Fixtures.RIVERSIDE, day)).thenReturn(List.of());
    when(rows.now()).thenReturn(Instant.parse("2026-07-01T10:00:00Z"));

    ScheduleView view =
        new ScheduleQuery(catalogs, rows).forDay(Fixtures.RIVERSIDE, summerDay, GroupBy.BAY);

    assertThat(view.date()).isEqualTo(summerDay);
    assertThat(view.items()).isEmpty();
    verify(rows).appointments(Fixtures.RIVERSIDE, day);
  }
}
