package com.keyloop.scheduler.hold;

import com.keyloop.scheduler.booking.event.AppointmentCommitted;
import com.keyloop.scheduler.booking.event.BookingListener;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Write-through after a booking commits (spec 8.6): a confirmed hold turns into confirmed
 * intervals; a booking without a hold just adds its intervals. Failures are logged by the caller;
 * the ledger then heals on the next conflict or marker expiry.
 */
@Component
public class LedgerBookingListener implements BookingListener {

  private final SlotLedger ledger;

  /** Creates the listener. */
  public LedgerBookingListener(SlotLedger ledger) {
    this.ledger = ledger;
  }

  @Override
  public void afterCommit(AppointmentCommitted event) {
    if (event.holdId() != null) {
      ledger.confirm(event.dealershipId(), event.localDate(), event.holdId(), event.reservations());
    } else {
      ledger.applyCommitted(
          event.dealershipId(), event.localDate(), List.of(), event.reservations());
    }
  }
}
