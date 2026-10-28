package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.ServiceTypeView;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.api.SlotUnavailableException;
import com.keyloop.scheduler.common.db.SqlStates;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.time.DbClock;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.hold.SlotLedger;
import com.keyloop.scheduler.outbox.OutboxWriter;
import com.keyloop.scheduler.query.AppointmentReadRepository;
import com.keyloop.scheduler.query.AppointmentView;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Dispatcher drag-and-drop and resize (spec 6.4, 8.4). The new time and resources are checked
 * against the dispatcher policy, the technician's skills and live customer holds before the
 * database is touched; then one transaction takes the advisory locks of the old and new resources,
 * moves the appointment at the {@code If-Match} version (412 otherwise) and its two reservations
 * (23P01 ⇒ 409 with the conflicting appointments), and writes the event. The Redis ledger follows
 * after commit.
 */
@Service
public class DispatcherChangeService {

  static final int MIN_DURATION = 5;
  static final int MAX_DURATION = 24 * 60;

  private static final Logger LOG = LoggerFactory.getLogger(DispatcherChangeService.class);
  private static final String AGGREGATE = "Appointment";
  private static final String RESCHEDULED_EVENT = "AppointmentRescheduled";

  private final CatalogService catalogs;
  private final AppointmentRepository appointments;
  private final ReservationRepository reservations;
  private final ResourceLocks locks;
  private final AppointmentReadRepository reads;
  private final OutboxWriter outbox;
  private final SlotLedger ledger;
  private final DbClock clock;
  private final TransactionOperations transactions;
  private final HoldGuard holds;

  /** Creates the service; {@code transactions} runs each attempt in a new transaction. */
  public DispatcherChangeService(
      CatalogService catalogs,
      AppointmentRepository appointments,
      ReservationRepository reservations,
      ResourceLocks locks,
      AppointmentReadRepository reads,
      OutboxWriter outbox,
      SlotLedger ledger,
      DbClock clock,
      @Qualifier(BookingConfig.BOOKING_TRANSACTIONS) TransactionOperations transactions) {
    this.catalogs = catalogs;
    this.appointments = appointments;
    this.reservations = reservations;
    this.locks = locks;
    this.reads = reads;
    this.outbox = outbox;
    this.ledger = ledger;
    this.clock = clock;
    this.transactions = transactions;
    this.holds = new HoldGuard(ledger);
  }

  /**
   * Moves and/or resizes the appointment; omitted fields keep their value. Not in the dealership ⇒
   * 404; stale version ⇒ 412; ON_HOLD or terminal ⇒ 409 INVALID_TRANSITION; IN_PROGRESS start
   * change ⇒ 422 START_FIXED; unqualified technician ⇒ 422; a customer's live hold ⇒ 409 SLOT_HELD
   * (unless {@code overrideHold}); taken ⇒ 409 SLOT_UNAVAILABLE with {@code conflicts}.
   */
  public AppointmentView change(
      CallerContext caller,
      UUID dealershipId,
      UUID appointmentId,
      long expectedVersion,
      DispatcherChangeRequest req) {
    Integer minutes = req.durationMinutes();
    if (minutes != null && (minutes < MIN_DURATION || minutes > MAX_DURATION)) {
      throw DispatcherResources.invalid(
          "durationMinutes", "must be between " + MIN_DURATION + " and " + MAX_DURATION);
    }
    boolean deadlockRetried = false;
    while (true) {
      Planned p = plan(dealershipId, appointmentId, expectedVersion, req);
      try {
        Moved moved = transactions.execute(status -> persist(p));
        writeThrough(p, moved);
        return moved.view();
      } catch (ReservationConflictException e) {
        markDirty(dealershipId, p.newDate());
        throw unavailable(p);
      } catch (RuntimeException e) {
        if (!deadlockRetried && SqlStates.has(e, SqlStates.DEADLOCK_DETECTED)) {
          deadlockRetried = true;
          continue;
        }
        throw e;
      }
    }
  }

  /** The checked target of one attempt. */
  private record Planned(
      AppointmentState state,
      DealershipView dealership,
      long expectedVersion,
      TimeRange period,
      ReservationRow bay,
      ReservationRow technician,
      LocalDate oldDate,
      LocalDate newDate) {}

  /** What a committed move produced. */
  private record Moved(Map<UUID, ReservationRow> moved, AppointmentView view) {}

  private Planned plan(
      UUID dealershipId, UUID appointmentId, long expectedVersion, DispatcherChangeRequest req) {
    AppointmentState state =
        appointments
            .findState(appointmentId)
            .filter(s -> s.dealershipId().equals(dealershipId))
            .orElseThrow(() -> ChangeDiagnosis.notFound(appointmentId));
    if (state.version() != expectedVersion) {
      throw ChangeDiagnosis.stale(appointmentId);
    }
    ResourceCatalog catalog = catalogs.get(dealershipId);
    DealershipView d = catalog.dealership();
    List<ServiceTypeView> services =
        DispatcherResources.services(catalog, appointments.serviceTypeIds(appointmentId));
    Instant start = req.start() != null ? req.start().toInstant() : state.period().start();
    int minutes =
        req.durationMinutes() != null ? req.durationMinutes() : (int) state.period().minutes();
    TimeRange period = new TimeRange(start, start.plus(Duration.ofMinutes(minutes)));
    Duration buffer = Duration.ofMinutes(d.bayBufferMinutes());
    Set<String> skills = new LinkedHashSet<>();
    services.forEach(s -> skills.add(s.requiredSkill()));
    LocalDateTime localStart = start.atZone(d.zoneId()).toLocalDateTime();
    JobPlan plan =
        new JobPlan(
            services,
            minutes,
            skills,
            period,
            period.extendEnd(buffer),
            localStart.toLocalDate(),
            localStart);
    BookingChannel.DISPATCHER
        .policy()
        .checkChange(state.status(), state.period(), plan, d, clock.now());
    UUID bayId = req.serviceBayId() != null ? req.serviceBayId() : state.serviceBayId();
    if (req.serviceBayId() != null) {
      DispatcherResources.bay(catalog, bayId);
    }
    UUID technicianId = req.technicianId() != null ? req.technicianId() : state.technicianId();
    if (req.technicianId() != null) {
      TechnicianView technician = DispatcherResources.technician(catalog, technicianId);
      DispatcherResources.requireQualified(technician, services);
    }
    TimeRange technicianPeriod = period;
    TimeRange bayPeriod = plan.bayPeriod();
    if (state.status() == AppointmentStatus.IN_PROGRESS) {
      List<ActiveReservation> current = reservations.activeRows(appointmentId);
      Instant segment = segmentStart(current, ReservationRow.TECHNICIAN).orElse(start);
      if (!period.end().isAfter(segment)) {
        throw DispatcherResources.invalid(
            "durationMinutes", "would end the job before its current work segment starts");
      }
      technicianPeriod = new TimeRange(segment, period.end());
      bayPeriod =
          new TimeRange(segmentStart(current, ReservationRow.BAY).orElse(segment), bayPeriod.end());
    }
    Map<UUID, TimeRange> targets = new LinkedHashMap<>();
    targets.put(technicianId, technicianPeriod);
    targets.put(bayId, bayPeriod);
    holds.clear(d, targets, req.overrideHold());
    return new Planned(
        state,
        d,
        expectedVersion,
        period,
        new ReservationRow(bayId, ReservationRow.BAY, bayPeriod),
        new ReservationRow(technicianId, ReservationRow.TECHNICIAN, technicianPeriod),
        state.period().start().atZone(d.zoneId()).toLocalDate(),
        localStart.toLocalDate());
  }

  /** Where the running work segment of a resource type started (it may differ after a resume). */
  private static Optional<Instant> segmentStart(List<ActiveReservation> current, String type) {
    return current.stream()
        .filter(r -> r.row().resourceType().equals(type))
        .map(r -> r.row().period().start())
        .findFirst();
  }

  private Moved persist(Planned p) {
    AppointmentState state = p.state();
    UUID id = state.id();
    locks.lockAll(
        List.of(
            state.serviceBayId(),
            state.technicianId(),
            p.bay().resourceId(),
            p.technician().resourceId()));
    OptionalLong version =
        appointments.markMoved(
            id,
            state.dealershipId(),
            p.expectedVersion(),
            p.bay().resourceId(),
            p.technician().resourceId(),
            p.period());
    if (version.isEmpty()) {
      throw appointments
              .findState(id)
              .filter(s -> s.dealershipId().equals(state.dealershipId()))
              .isEmpty()
          ? ChangeDiagnosis.notFound(id)
          : ChangeDiagnosis.stale(id);
    }
    Map<UUID, ReservationRow> moved = reservations.move(id, List.of(p.bay(), p.technician()));
    outbox.append(
        AGGREGATE,
        id,
        state.dealershipId(),
        RESCHEDULED_EVENT,
        version.getAsLong(),
        new AppointmentRescheduledEvent(
            id,
            state.customerId(),
            state.period().start(),
            state.period().end(),
            state.serviceBayId(),
            state.technicianId(),
            p.period().start(),
            p.period().end(),
            p.bay().resourceId(),
            p.technician().resourceId(),
            p.dealership().zoneId().getId(),
            state.status().name()));
    return new Moved(moved, reads.find(id).orElseThrow());
  }

  /** The refused move: the appointments holding the target, read after the rollback. */
  private SlotUnavailableException unavailable(Planned p) {
    List<UUID> conflicts =
        reservations.conflictingAppointments(
            p.state().dealershipId(),
            List.of(p.technician().resourceId()),
            p.technician().period(),
            List.of(p.bay().resourceId()),
            p.bay().period());
    return new SlotUnavailableException(
        DispatcherResources.taken(p.dealership(), p.period().start()),
        List.of(),
        DispatcherResources.others(conflicts, p.state().id()));
  }

  /** After commit: the moved reservations replace their old ledger entries. */
  private void writeThrough(Planned p, Moved m) {
    UUID dealershipId = p.state().dealershipId();
    List<UUID> ids = List.copyOf(m.moved().keySet());
    try {
      if (p.oldDate().equals(p.newDate())) {
        ledger.applyCommitted(dealershipId, p.newDate(), ids, m.moved());
      } else {
        ledger.applyCommitted(dealershipId, p.oldDate(), ids, Map.of());
        ledger.applyCommitted(dealershipId, p.newDate(), List.of(), m.moved());
      }
    } catch (RuntimeException e) {
      LOG.warn("Ledger write-through of moved {} failed: {}", p.state().id(), e.toString());
      markDirty(dealershipId, p.oldDate());
      if (!p.oldDate().equals(p.newDate())) {
        markDirty(dealershipId, p.newDate());
      }
    }
  }

  private void markDirty(UUID dealershipId, LocalDate date) {
    try {
      ledger.markDirty(dealershipId, date);
    } catch (RuntimeException e) {
      LOG.warn("Could not mark ledger of {} dirty: {}", dealershipId, e.toString());
    }
  }
}
