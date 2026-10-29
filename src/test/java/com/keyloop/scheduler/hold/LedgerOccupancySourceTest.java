package com.keyloop.scheduler.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.booking.core.DbOccupancySource;
import com.keyloop.scheduler.booking.domain.Occupancy;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LedgerOccupancySourceTest {

  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final LocalDate SUNDAY = LocalDate.of(2026, 11, 1);
  private static final Occupancy FROM_DB =
      new Occupancy(
          Map.of(
              Fixtures.SAM,
              List.of(
                  new TimeRange(
                      Instant.parse("2026-11-02T09:00:00Z"),
                      Instant.parse("2026-11-02T10:00:00Z")))));

  private final SlotLedger ledger = mock(SlotLedger.class);
  private final DbOccupancySource database = mock(DbOccupancySource.class);
  private final LedgerOccupancySource source = new LedgerOccupancySource(ledger, database);

  @Test
  void ledgerThatCannotBeRebuiltIsNeverUsedTheDatabaseAnswers() {
    doThrow(new LedgerUnavailableException("overtaken"))
        .when(ledger)
        .ensureLoaded(any(), any(), any());
    when(database.forDay(Fixtures.RIVERSIDE, MONDAY, CatalogFixtures.riverside()))
        .thenReturn(FROM_DB);

    assertThat(source.forDay(Fixtures.RIVERSIDE, MONDAY, CatalogFixtures.riverside()))
        .isEqualTo(FROM_DB);
    org.mockito.Mockito.verify(ledger, org.mockito.Mockito.never())
        .occupancy(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
  }

  @Test
  void loadedLedgerAnswersWithOthersHolds() {
    when(ledger.occupancy(Fixtures.RIVERSIDE, MONDAY, true, Fixtures.ALICE_SUB))
        .thenReturn(FROM_DB);

    assertThat(
            source.forDay(
                Fixtures.RIVERSIDE, MONDAY, CatalogFixtures.riverside(), Fixtures.ALICE_SUB))
        .isEqualTo(FROM_DB);
    verifyNoInteractions(database);
  }

  @Test
  void closedDayIsEmptyWithoutAsking() {
    assertThat(source.forDay(Fixtures.RIVERSIDE, SUNDAY, CatalogFixtures.riverside()))
        .isEqualTo(Occupancy.empty());
    verifyNoInteractions(ledger, database);
  }

  @Test
  void bugsAreNotHiddenByTheFallback() {
    doThrow(new IllegalStateException("bug")).when(ledger).ensureLoaded(any(), any(), any());

    assertThatThrownBy(() -> source.forDay(Fixtures.RIVERSIDE, MONDAY, CatalogFixtures.riverside()))
        .isInstanceOf(IllegalStateException.class);
  }
}
