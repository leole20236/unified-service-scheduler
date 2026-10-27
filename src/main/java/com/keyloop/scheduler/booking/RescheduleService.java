package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.availability.AvailabilityCalculator;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.common.api.BusinessRuleException;
import com.keyloop.scheduler.common.api.HoldsUnavailableException;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.api.SlotUnavailableException;
import com.keyloop.scheduler.common.db.SqlStates;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.time.DbClock;
import com.keyloop.scheduler.customer.CustomerService;
import com.keyloop.scheduler.hold.HoldPayload;
import com.keyloop.scheduler.hold.LedgerFailures;
import com.keyloop.scheduler.hold.SlotLedger;
import com.keyloop.scheduler.hold.SlotLedger.HoldLocation;
import com.keyloop.scheduler.hold.SlotLedger.StoredHold;
import com.keyloop.scheduler.outbox.OutboxWriter;
import com.keyloop.scheduler.query.AppointmentReadRepository;
import com.keyloop.scheduler.query.AppointmentView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * A customer moves their own appointment to the time and resource pair they hold (spec 6.4, 8.4,
 * 8.6). One READ COMMITTED transaction: advisory locks on the old and new resources, a conditional
 * UPDATE of the appointment at the expected version, both reservations moved, the event written.
 * The Redis ledger follows after commit. The EXCLUDE constraint still decides every race.
 */
@Service
public class RescheduleService {

  static final int MAX_ALTERNATIVES = 5;

  private static final Logger LOG = LoggerFactory.getLogger(RescheduleService.class);
  private static final String AGGREGATE = "Appointment";
  private static final String RESCHEDULED_EVENT = "AppointmentRescheduled";
  private static final String HOLDS_DOWN =
      "Holds are unavailable right now; you can still book directly.";

  private final CustomerService customers;
  private final CatalogService catalogs;
  private final AppointmentRepository appointments;
  private final ReservationRepository reservations;
  private final ResourceLocks locks;
  private final AppointmentReadRepository reads;
  private final OutboxWriter outbox;
  private final SlotLedger ledger;
  private final DbClock clock;
  private final OccupancySource dailyOccupancy;
  private final TransactionOperations transactions;

  /** Creates the service; {@code transactions} runs each attempt in a new transaction. */
  public RescheduleService(
      CustomerService customers,
      CatalogService catalogs,
      AppointmentRepository appointments,
      ReservationRepository reservations,
      ResourceLocks locks,
      AppointmentReadRepository reads,
      OutboxWriter outbox,
      SlotLedger ledger,
      DbClock clock,
      OccupancySource dailyOccupancy,
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
    this.dailyOccupancy = dailyOccupancy;
    this.transactions = transactions;
  }

  /**
   * Moves the caller's appointment to the held time and pair. Not theirs ⇒ 404; stale version ⇒
   * 412; hold of someone else or gone ⇒ 404; hold for another dealership or other services ⇒ 422
   * HOLD_MISMATCH; not BOOKED ⇒ 409; within 24 hours or an invalid new time ⇒ 422; the pair taken
   * meanwhile ⇒ 409 SLOT_UNAVAILABLE with alternatives.
   */
  public AppointmentView customerReschedule(
      CallerContext caller, UUID appointmentId, long expectedVersion, UUID holdId) {
    UUID customerId = customers.currentCustomer(caller);
    AtomicReference<Planned> planned = new AtomicReference<>();
    boolean deadlockRetried = false;
    while (true) {
      try {
        Moved moved =
            transactions.execute(
                status ->
                    persist(caller, customerId, appointmentId, expectedVersion, holdId, planned));
        writeThrough(moved);
        return moved.view();
      } catch (ReservationConflictException e) {
        Planned p = planned.get();
        markDirty(p.dealershipId(), p.newDate());
        throw unavailable(caller, p);
      } catch (RuntimeException e) {
        if (!deadlockRetried && SqlStates.has(e, SqlStates.DEADLOCK_DETECTED)) {
          deadlockRetried = true;
          continue;
        }
        throw e;
      }
    }
  }

  /** What the conflict handler needs to explain a refusal. */
  private record Planned(
      UUID dealershipId, ResourceCatalog catalog, JobPlan plan, LocalDate newDate) {}

  /** What a committed reschedule produced. */
  private record Moved(
      UUID dealershipId,
      UUID userId,
      UUID holdId,
      LocalDate oldDate,
      LocalDate newDate,
      Map<UUID, ReservationRow> moved,
      AppointmentView view) {}

  private Moved persist(
      CallerContext caller,
      UUID customerId,
      UUID appointmentId,
      long expectedVersion,
      UUID holdId,
      AtomicReference<Planned> planned) {
    AppointmentState state =
        appointments
            .findState(appointmentId)
            .filter(s -> s.customerId().equals(customerId))
            .orElseThrow(() -> ChangeDiagnosis.notFound(appointmentId));
    if (state.version() != expectedVersion) {
      throw ChangeDiagnosis.stale(appointmentId);
    }
    ResourceCatalog catalog = catalogs.get(state.dealershipId());
    DealershipView dealership = catalog.dealership();
    List<UUID> serviceTypeIds = appointments.serviceTypeIds(appointmentId);
    StoredHold hold =
        heldFor(caller, appointmentId, expectedVersion, holdId, state, serviceTypeIds);
    JobPlan plan =
        JobPlanner.plan(
            catalog,
            serviceTypeIds,
            OffsetDateTime.ofInstant(hold.period().start(), ZoneOffset.UTC));
    Instant now = clock.now();
    BookingChannel.CUSTOMER
        .policy()
        .checkChange(state.status(), state.period(), plan, dealership, now);
    planned.set(new Planned(state.dealershipId(), catalog, plan, plan.localDate()));
    ResourcePair pair = hold.pair();
    locks.lockAll(
        List.of(state.serviceBayId(), state.technicianId(), pair.bayId(), pair.technicianId()));
    OptionalLong version =
        appointments.markRescheduled(
            appointmentId,
            customerId,
            expectedVersion,
            pair.bayId(),
            pair.technicianId(),
            plan.period());
    if (version.isEmpty()) {
      throw ChangeDiagnosis.of(
          appointmentId,
          customerId,
          OptionalLong.of(expectedVersion),
          appointments.findState(appointmentId),
          now);
    }
    Map<UUID, ReservationRow> moved =
        reservations.move(
            appointmentId,
            List.of(
                new ReservationRow(pair.bayId(), ReservationRow.BAY, plan.bayPeriod()),
                new ReservationRow(pair.technicianId(), ReservationRow.TECHNICIAN, plan.period())));
    outbox.append(
        AGGREGATE,
        appointmentId,
        state.dealershipId(),
        RESCHEDULED_EVENT,
        version.getAsLong(),
        new AppointmentRescheduledEvent(
            appointmentId,
            customerId,
            state.period().start(),
            state.period().end(),
            state.serviceBayId(),
            state.technicianId(),
            plan.period().start(),
            plan.period().end(),
            pair.bayId(),
            pair.technicianId(),
            dealership.zoneId().getId(),
            AppointmentStatus.BOOKED.name()));
    AppointmentView view = reads.find(appointmentId).orElseThrow();
    return new Moved(
        state.dealershipId(),
        caller.userId(),
        holdId,
        state.period().start().atZone(dealership.zoneId()).toLocalDate(),
        plan.localDate(),
        moved,
        view);
  }

  /**
   * The caller's live hold, checked against the appointment. Someone else's hold or one that is
   * gone is a 404 (a gone hold after a concurrent change of the appointment is the 412 the client
   * should see); a hold for another dealership or other services is a 422.
   */
  private StoredHold heldFor(
      CallerContext caller,
      UUID appointmentId,
      long expectedVersion,
      UUID holdId,
      AppointmentState state,
      List<UUID> serviceTypeIds) {
    Optional<HoldLocation> location;
    Optional<StoredHold> hold;
    try {
      location = ledger.locate(holdId);
      hold = location.flatMap(l -> ledger.getHold(l.dealershipId(), l.date(), holdId));
    } catch (RuntimeException e) {
      if (!LedgerFailures.isRedisFailure(e)) {
        throw e;
      }
      LOG.warn("Hold {} unreadable: {}", holdId, e.toString());
      throw new HoldsUnavailableException(HOLDS_DOWN);
    }
    if (hold.isEmpty()) {
      appointments
          .findState(appointmentId)
          .filter(s -> s.version() != expectedVersion)
          .ifPresent(
              s -> {
                throw ChangeDiagnosis.stale(appointmentId);
              });
      throw new NotFoundException("Hold not found.");
    }
    StoredHold h = hold.get();
    if (!h.userId().equals(caller.userId())) {
      throw new NotFoundException("Hold not found.");
    }
    if (!location.get().dealershipId().equals(state.dealershipId())) {
      throw new BusinessRuleException(
          ProblemCodes.HOLD_MISMATCH, "Your hold is for another dealership.");
    }
    List<UUID> heldServices = HoldPayload.parse(h.payloadJson()).serviceTypeIds();
    if (!new HashSet<>(heldServices).equals(new HashSet<>(serviceTypeIds))) {
      throw new BusinessRuleException(
          ProblemCodes.HOLD_MISMATCH,
          "Your hold is for other services than this appointment; hold the same services again.");
    }
    return h;
  }

  /** After commit: old reservations leave the ledger, new ones appear, the hold is consumed. */
  private void writeThrough(Moved m) {
    try {
      List<UUID> ids = List.copyOf(m.moved().keySet());
      if (m.oldDate().equals(m.newDate())) {
        ledger.applyCommitted(m.dealershipId(), m.newDate(), ids, m.moved());
      } else {
        ledger.applyCommitted(m.dealershipId(), m.oldDate(), ids, Map.of());
        ledger.applyCommitted(m.dealershipId(), m.newDate(), List.of(), m.moved());
      }
      ledger.release(m.dealershipId(), m.newDate(), m.holdId(), m.userId());
    } catch (RuntimeException e) {
      LOG.warn("Ledger write-through of rescheduled {} failed: {}", m.view().id(), e.toString());
      markDirty(m.dealershipId(), m.oldDate());
      markDirty(m.dealershipId(), m.newDate());
    }
  }

  /** A refused write means the ledger let a taken slot through: rebuild it before its next use. */
  private void markDirty(UUID dealershipId, LocalDate date) {
    try {
      ledger.markDirty(dealershipId, date);
    } catch (RuntimeException e) {
      LOG.warn("Could not mark ledger of {} dirty: {}", dealershipId, e.toString());
    }
  }

  private SlotUnavailableException unavailable(CallerContext caller, Planned p) {
    DealershipView d = p.catalog().dealership();
    BookingPolicy policy = BookingChannel.CUSTOMER.policy();
    Instant now = clock.now();
    List<Instant> alternatives =
        AvailabilityCalculator.alternatives(
                p.catalog(),
                p.plan(),
                date -> dailyOccupancy.forDay(d.id(), date, d, caller.userId()),
                policy.earliestStart(now),
                policy.latestStart(now),
                MAX_ALTERNATIVES)
            .stream()
            .map(OffsetDateTime::toInstant)
            .toList();
    return new SlotUnavailableException(SlotMessages.unavailable(p.plan(), d), alternatives, null);
  }
}
