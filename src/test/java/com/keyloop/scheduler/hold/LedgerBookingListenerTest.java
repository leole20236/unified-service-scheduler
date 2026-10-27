package com.keyloop.scheduler.hold;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.keyloop.scheduler.booking.AppointmentCommitted;
import com.keyloop.scheduler.booking.ReservationRow;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerBookingListenerTest {

  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final Map<UUID, ReservationRow> ROWS =
      Map.of(
          UUID.randomUUID(),
          new ReservationRow(
              Fixtures.SAM,
              ReservationRow.TECHNICIAN,
              new TimeRange(
                  Instant.parse("2026-11-02T09:00:00Z"), Instant.parse("2026-11-02T09:45:00Z"))));

  private final SlotLedger ledger = mock(SlotLedger.class);
  private final LedgerBookingListener listener = new LedgerBookingListener(ledger);

  @Test
  void confirmedHoldBecomesConfirmedIntervals() {
    UUID hold = UUID.randomUUID();

    listener.afterCommit(
        new AppointmentCommitted(UUID.randomUUID(), Fixtures.RIVERSIDE, MONDAY, hold, ROWS));

    verify(ledger).confirm(Fixtures.RIVERSIDE, MONDAY, hold, ROWS);
    verifyNoMoreInteractions(ledger);
  }

  @Test
  void bookingWithoutHoldIsWrittenThrough() {
    listener.afterCommit(
        new AppointmentCommitted(UUID.randomUUID(), Fixtures.RIVERSIDE, MONDAY, null, ROWS));

    verify(ledger).applyCommitted(Fixtures.RIVERSIDE, MONDAY, List.of(), ROWS);
    verifyNoMoreInteractions(ledger);
  }
}
