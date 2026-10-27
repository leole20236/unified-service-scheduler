package com.keyloop.scheduler.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DbOccupancySourceTest {

  private final OccupancyRepository repository = mock(OccupancyRepository.class);
  private final DbOccupancySource source = new DbOccupancySource(repository);

  @Test
  void loadsOpeningWindowPlusBayBuffer() {
    Occupancy loaded = Occupancy.empty();
    when(repository.load(eq(Fixtures.HUDSON), any())).thenReturn(loaded);

    Occupancy result =
        source.forDay(Fixtures.HUDSON, LocalDate.of(2026, 11, 2), CatalogFixtures.hudson());

    assertThat(result).isSameAs(loaded);
    ArgumentCaptor<TimeRange> window = ArgumentCaptor.forClass(TimeRange.class);
    verify(repository).load(eq(Fixtures.HUDSON), window.capture());
    // 09:00-17:00 EST plus the 10 minute buffer
    assertThat(window.getValue().start()).isEqualTo(Instant.parse("2026-11-02T14:00:00Z"));
    assertThat(window.getValue().end()).isEqualTo(Instant.parse("2026-11-02T22:10:00Z"));
  }

  @Test
  void closedDayNeedsNoQuery() {
    Occupancy result =
        source.forDay(Fixtures.RIVERSIDE, LocalDate.of(2026, 11, 1), CatalogFixtures.riverside());

    assertThat(result.byResource()).isEmpty();
    verifyNoInteractions(repository);
  }
}
