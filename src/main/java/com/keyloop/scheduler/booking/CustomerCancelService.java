package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.time.DbClock;
import com.keyloop.scheduler.customer.CustomerService;
import com.keyloop.scheduler.hold.SlotLedger;
import com.keyloop.scheduler.outbox.OutboxWriter;
import com.keyloop.scheduler.query.AppointmentReadRepository;
import com.keyloop.scheduler.query.AppointmentView;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * A customer cancels their own appointment (spec 6.4, 8.4): one conditional UPDATE that checks
 * status, ownership, the 24 hour cutoff against the database clock and, optionally, the version;
 * the reservations are deactivated and the event written in the same transaction, the Redis ledger
 * follows after commit.
 */
@Service
public class CustomerCancelService {

  private static final Logger LOG = LoggerFactory.getLogger(CustomerCancelService.class);
  private static final String AGGREGATE = "Appointment";
  private static final String CANCELLED_EVENT = "AppointmentCancelled";

  private final CustomerService customers;
  private final CatalogService catalogs;
  private final AppointmentRepository appointments;
  private final ReservationRepository reservations;
  private final ResourceLocks locks;
  private final AppointmentReadRepository reads;
  private final OutboxWriter outbox;
  private final SlotLedger ledger;
  private final DbClock clock;
  private final TransactionOperations transactions;

  /** Creates the service; {@code transactions} runs the cancel in one new transaction. */
  public CustomerCancelService(
      CustomerService customers,
      CatalogService catalogs,
      AppointmentRepository appointments,
      ReservationRepository reservations,
      ResourceLocks locks,
      AppointmentReadRepository reads,
      OutboxWriter outbox,
      SlotLedger ledger,
      DbClock clock,
      @Qualifier(BookingConfig.BOOKING_TRANSACTIONS) TransactionOperations transactions) {
    this.customers = customers;
    this.catalogs = catalogs;
    this.appointments = appointments;
    this.reservations = reservations;
    this.locks = locks;
    this.reads = reads;
    this.outbox = outbox;
    this.ledger = ledger;
    this.clock = clock;
    this.transactions = transactions;
  }

  /** Cancels without a version precondition. */
  public AppointmentView cancel(CallerContext caller, UUID appointmentId) {
    return cancel(caller, appointmentId, OptionalLong.empty());
  }

  /**
   * Cancels the caller's appointment; a given {@code expectedVersion} must match (412 otherwise).
   * Not theirs ⇒ 404, not BOOKED ⇒ 409 INVALID_TRANSITION, within 24 hours ⇒ 422
   * CHANGE_WINDOW_CLOSED.
   */
  public AppointmentView cancel(
      CallerContext caller, UUID appointmentId, OptionalLong expectedVersion) {
    UUID customerId = customers.currentCustomer(caller);
    Cancelled done =
        transactions.execute(status -> persist(customerId, appointmentId, expectedVersion));
    writeThrough(done);
    return done.view();
  }

  /** What a committed cancel produced. */
  private record Cancelled(
      UUID dealershipId, LocalDate localDate, List<UUID> reservationIds, AppointmentView view) {}

  private Cancelled persist(UUID customerId, UUID appointmentId, OptionalLong expectedVersion) {
    AppointmentState state =
        appointments
            .findState(appointmentId)
            .filter(s -> s.customerId().equals(customerId))
            .orElseThrow(() -> ChangeDiagnosis.notFound(appointmentId));
    // Writers of these resources queue on the advisory locks before any row is touched (spec 8.1).
    locks.lockAll(List.of(state.serviceBayId(), state.technicianId()));
    OptionalLong version = appointments.markCancelled(appointmentId, customerId, expectedVersion);
    if (version.isEmpty()) {
      throw ChangeDiagnosis.of(
          appointmentId,
          customerId,
          expectedVersion,
          appointments.findState(appointmentId),
          clock.now());
    }
    List<UUID> reservationIds = reservations.deactivate(appointmentId);
    ZoneId zone = catalogs.get(state.dealershipId()).dealership().zoneId();
    outbox.append(
        AGGREGATE,
        appointmentId,
        state.dealershipId(),
        CANCELLED_EVENT,
        version.getAsLong(),
        new AppointmentCancelledEvent(
            appointmentId,
            customerId,
            state.period().start(),
            state.period().end(),
            state.serviceBayId(),
            state.technicianId(),
            zone.getId(),
            AppointmentStatus.CANCELLED.name()));
    AppointmentView view = reads.find(appointmentId).orElseThrow();
    return new Cancelled(
        state.dealershipId(),
        state.period().start().atZone(zone).toLocalDate(),
        reservationIds,
        view);
  }

  /**
   * After commit: the cancelled reservations leave the ledger; a Redis failure only heals later.
   */
  private void writeThrough(Cancelled done) {
    try {
      ledger.applyCommitted(done.dealershipId(), done.localDate(), done.reservationIds(), Map.of());
    } catch (RuntimeException e) {
      LOG.warn("Ledger write-through of cancelled {} failed: {}", done.view().id(), e.toString());
      try {
        ledger.markDirty(done.dealershipId(), done.localDate());
      } catch (RuntimeException again) {
        LOG.warn("Could not mark ledger of {} dirty: {}", done.dealershipId(), again.toString());
      }
    }
  }
}
