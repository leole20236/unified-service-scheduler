package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.api.BusinessRuleException;
import com.keyloop.scheduler.common.api.ProblemCodes;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Dispatcher status actions (spec 6.2, 8.4): each is one conditional {@code UPDATE … WHERE status =
 * ANY(:allowedFrom)} at a version, so two concurrent actions cannot both succeed, followed in the
 * same transaction by its reservation effects and its events. Actions that touch reservations first
 * take one {@code lockAll} over every resource involved (old and new), before the appointment row
 * is updated; if the appointment changed meanwhile and the caller gave no {@code If-Match}, the
 * whole attempt starts again on the new state (at most three times). The Redis ledger follows after
 * commit, per local day of each reservation's own period.
 *
 * <p>RESUME may continue on a later day (waiting for parts until tomorrow). It reserves the planned
 * minutes not yet worked: the sum of the booked services' durations minus {@code worked_minutes}
 * (at least 5). Known limitation: a dispatcher resize made before the hold is not carried into
 * RESUME (there is no stored planned duration; the appointment's period stops describing it after
 * the first resume).
 */
@Service
public class TransitionService {

  static final int MAX_ATTEMPTS = 3;
  static final Duration NO_SHOW_GRACE = Duration.ofMinutes(30);
  static final Duration RESUME_GRACE = Duration.ofMinutes(15);
  static final int MIN_REMAINING_MINUTES = 5;

  private static final String AGGREGATE = "Appointment";
  private static final String STATUS_CHANGED = "AppointmentStatusChanged";
  private static final String CANCELLED = "AppointmentCancelled";
  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

  private final CatalogService catalogs;
  private final AppointmentRepository appointments;
  private final ReservationRepository reservations;
  private final ResourceLocks locks;
  private final AppointmentReadRepository reads;
  private final OutboxWriter outbox;
  private final DbClock clock;
  private final TransactionOperations transactions;
  private final HoldGuard holds;
  private final LedgerWrites ledgerWrites;

  /** Creates the service; {@code transactions} runs each attempt in a new transaction. */
  public TransitionService(
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
    this.clock = clock;
    this.transactions = transactions;
    this.holds = new HoldGuard(ledger);
    this.ledgerWrites = new LedgerWrites(ledger);
  }

  /**
   * Applies the action. Not in the dealership ⇒ 404; {@code expectedVersion} stale ⇒ 412; not
   * allowed from the current status ⇒ 409 INVALID_TRANSITION; NO_SHOW before start + 30 minutes ⇒
   * 422 NO_SHOW_TOO_EARLY; a RESUME into a taken slot ⇒ 409 SLOT_UNAVAILABLE (or SLOT_HELD).
   *
   * @param actionBody {@link HoldActionRequest} (required for HOLD), {@link ResumeActionRequest},
   *     {@link CancelActionRequest} or null
   */
  public AppointmentView apply(
      CallerContext caller,
      UUID dealershipId,
      UUID appointmentId,
      Action action,
      Object actionBody,
      OptionalLong expectedVersion) {
    boolean deadlockRetried = false;
    int attempt = 1;
    while (true) {
      boolean last = attempt >= MAX_ATTEMPTS;
      Prepared p = prepare(dealershipId, appointmentId, action, actionBody, expectedVersion);
      try {
        Done done = transactions.execute(status -> persist(p, !last));
        ledgerWrites.apply(p.dealership(), done.removed(), done.added());
        return done.view();
      } catch (MovedUnderUsException e) {
        attempt++;
      } catch (ReservationConflictException e) {
        ledgerWrites.markDirty(dealershipId, p.newDate());
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

  /** Signal to roll back and start over: the appointment changed while we queued. */
  private static final class MovedUnderUsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    MovedUnderUsException() {
      super("Appointment changed while waiting for its resources", null, false, false);
    }
  }

  /** One attempt's plan, made from the state read at its start. */
  private record Prepared(
      Action action,
      AppointmentState state,
      DealershipView dealership,
      OptionalLong expected,
      StatusChange change,
      List<UUID> lockSet,
      boolean releaseBay,
      String reason,
      List<ReservationRow> resumeRows,
      LocalDate newDate) {}

  /** What a committed action changed. */
  private record Done(
      Map<UUID, TimeRange> removed, Map<UUID, ReservationRow> added, AppointmentView view) {}

  private Prepared prepare(
      UUID dealershipId, UUID appointmentId, Action action, Object body, OptionalLong expected) {
    AppointmentState state =
        appointments
            .findState(appointmentId)
            .filter(s -> s.dealershipId().equals(dealershipId))
            .orElseThrow(() -> ChangeDiagnosis.notFound(appointmentId));
    if (expected.isPresent() && expected.getAsLong() != state.version()) {
      throw ChangeDiagnosis.stale(appointmentId);
    }
    if (!AppointmentStateMachine.allows(action, state.status())) {
      throw AppointmentStateMachine.refusal(action, state.status());
    }
    ResourceCatalog catalog = catalogs.get(dealershipId);
    DealershipView d = catalog.dealership();

    List<UUID> lockSet = List.of(state.serviceBayId(), state.technicianId());
    boolean releaseBay = false;
    String reason = null;
    List<ReservationRow> resumeRows = null;
    LocalDate newDate = state.period().start().atZone(d.zoneId()).toLocalDate();
    StatusChange change;
    Set<AppointmentStatus> from = AppointmentStateMachine.allowedFrom(action);
    AppointmentStatus to = AppointmentStateMachine.target(action);
    switch (action) {
      case START -> change = new StatusChange(from, to, false, true, false, false, null, null);
      case HOLD -> {
        HoldActionRequest hold = body instanceof HoldActionRequest h ? h : null;
        if (hold == null || hold.holdReason() == null) {
          throw DispatcherResources.invalid(
              "holdReason", "must be one of " + List.of(HoldReason.values()));
        }
        releaseBay = hold.releaseBay();
        change = new StatusChange(from, to, false, false, true, false, hold.holdReason(), null);
      }
      case COMPLETE -> change = new StatusChange(from, to, false, false, true, true, null, null);
      case NO_SHOW -> {
        if (clock.now().isBefore(state.period().start().plus(NO_SHOW_GRACE))) {
          throw noShowTooEarly(d, state);
        }
        change = new StatusChange(from, to, true, false, false, false, null, null);
      }
      case CANCEL -> {
        reason = body instanceof CancelActionRequest c ? c.reason() : null;
        change = new StatusChange(from, to, false, false, false, false, null, null);
      }
      case RESUME -> {
        StatusChange.Resume resume =
            resumeTarget(catalog, state, body instanceof ResumeActionRequest r ? r : null);
        TimeRange bayPeriod = resume.period().extendEnd(Duration.ofMinutes(d.bayBufferMinutes()));
        resumeRows =
            List.of(
                new ReservationRow(resume.serviceBayId(), ReservationRow.BAY, bayPeriod),
                new ReservationRow(
                    resume.technicianId(), ReservationRow.TECHNICIAN, resume.period()));
        lockSet =
            List.of(
                state.serviceBayId(),
                state.technicianId(),
                resume.serviceBayId(),
                resume.technicianId());
        newDate = resume.period().start().atZone(d.zoneId()).toLocalDate();
        change = new StatusChange(from, to, false, false, false, false, null, resume);
      }
      default -> // CHECK_IN
          change = new StatusChange(from, to, false, false, false, false, null, null);
    }
    return new Prepared(
        action, state, d, expected, change, lockSet, releaseBay, reason, resumeRows, newDate);
  }

  /**
   * Where the job continues: from {@code resumeAt} (default now, at most 15 minutes ago) for the
   * planned minutes not yet worked (at least 5), on the given or the current technician and bay.
   * Checked against live customer holds before the database is touched.
   */
  private StatusChange.Resume resumeTarget(
      ResourceCatalog catalog, AppointmentState state, ResumeActionRequest request) {
    ResumeActionRequest r =
        request == null ? new ResumeActionRequest(null, null, null, false) : request;
    Instant now = clock.now();
    Instant resumeAt = r.resumeAt() == null ? now : r.resumeAt().toInstant();
    if (resumeAt.isBefore(now.minus(RESUME_GRACE))) {
      throw DispatcherResources.invalid("resumeAt", "must not be more than 15 minutes in the past");
    }
    if (resumeAt.isAfter(now.plus(PolicyChecks.MAX_AHEAD))) {
      throw DispatcherResources.invalid("resumeAt", "must be within 60 days");
    }
    UUID technicianId = r.technicianId() != null ? r.technicianId() : state.technicianId();
    UUID bayId = r.serviceBayId() != null ? r.serviceBayId() : state.serviceBayId();
    TechnicianView technician = DispatcherResources.technician(catalog, technicianId);
    DispatcherResources.bay(catalog, bayId);
    DispatcherResources.requireQualified(
        technician, DispatcherResources.services(catalog, appointments.serviceTypeIds(state.id())));
    AppointmentProgress progress =
        appointments.progress(state.id()).orElseThrow(() -> ChangeDiagnosis.notFound(state.id()));
    int remaining =
        Math.max(MIN_REMAINING_MINUTES, progress.plannedMinutes() - progress.workedMinutes());
    TimeRange period = new TimeRange(resumeAt, resumeAt.plus(Duration.ofMinutes(remaining)));
    DealershipView d = catalog.dealership();
    Map<UUID, TimeRange> targets = new LinkedHashMap<>();
    targets.put(technicianId, period);
    targets.put(bayId, period.extendEnd(Duration.ofMinutes(d.bayBufferMinutes())));
    holds.clear(d, targets, r.overrideHold());
    return new StatusChange.Resume(bayId, technicianId, period);
  }

  private Done persist(Prepared p, boolean mayRestart) {
    AppointmentState state = p.state();
    UUID id = state.id();
    if (p.action().touchesReservations()) {
      locks.lockAll(p.lockSet());
    }
    long version = p.expected().orElse(state.version());
    Transitioned done =
        appointments
            .transition(id, state.dealershipId(), version, p.change())
            .orElseThrow(() -> diagnose(p, mayRestart));
    // Each reservation's own period decides which ledger days it leaves (a resumed job's
    // reservations may sit on a later day than the appointment's start).
    Map<UUID, TimeRange> before = new LinkedHashMap<>();
    if (p.action().touchesReservations()) {
      reservations.activeRows(id).forEach(r -> before.put(r.reservationId(), r.row().period()));
    }
    List<UUID> removed = new ArrayList<>();
    Map<UUID, ReservationRow> added = new LinkedHashMap<>();
    switch (p.action()) {
      case CANCEL, NO_SHOW -> removed.addAll(reservations.deactivate(id));
      case HOLD -> {
        truncate(id, ReservationRow.TECHNICIAN, Duration.ZERO, removed, added);
        if (p.releaseBay()) {
          truncate(id, ReservationRow.BAY, Duration.ZERO, removed, added);
        }
      }
      case COMPLETE -> {
        truncate(id, ReservationRow.TECHNICIAN, Duration.ZERO, removed, added);
        truncate(
            id,
            ReservationRow.BAY,
            Duration.ofMinutes(p.dealership().bayBufferMinutes()),
            removed,
            added);
      }
      case RESUME -> {
        removed.addAll(reservations.deactivate(id));
        added.putAll(reservations.insertAll(id, state.dealershipId(), p.resumeRows()));
      }
      default -> {
        // CHECK_IN and START change the status only.
      }
    }
    writeEvents(p, done);
    Map<UUID, TimeRange> removedPeriods = new LinkedHashMap<>();
    removed.forEach(r -> removedPeriods.put(r, before.getOrDefault(r, state.period())));
    return new Done(removedPeriods, added, reads.find(id).orElseThrow());
  }

  private void truncate(
      UUID appointmentId,
      String resourceType,
      Duration extra,
      List<UUID> removed,
      Map<UUID, ReservationRow> added) {
    for (TruncatedReservation t : reservations.truncate(appointmentId, resourceType, extra)) {
      removed.add(t.reservationId());
      if (t.active()) {
        added.put(
            t.reservationId(), new ReservationRow(t.resourceId(), t.resourceType(), t.remaining()));
      }
    }
  }

  private void writeEvents(Prepared p, Transitioned done) {
    AppointmentState state = p.state();
    StatusChange change = p.change();
    StatusChange.Resume resume = change.resume();
    String zone = p.dealership().zoneId().getId();
    outbox.append(
        AGGREGATE,
        state.id(),
        state.dealershipId(),
        STATUS_CHANGED,
        done.version(),
        new AppointmentStatusChangedEvent(
            state.id(),
            state.customerId(),
            state.status().name(),
            change.to().name(),
            change.holdReason() == null ? null : change.holdReason().name(),
            p.reason(),
            done.period().start(),
            done.period().end(),
            resume == null ? state.serviceBayId() : resume.serviceBayId(),
            resume == null ? state.technicianId() : resume.technicianId(),
            zone));
    if (p.action() == Action.CANCEL) {
      outbox.append(
          AGGREGATE,
          state.id(),
          state.dealershipId(),
          CANCELLED,
          done.version(),
          new AppointmentCancelledEvent(
              state.id(),
              state.customerId(),
              done.period().start(),
              done.period().end(),
              state.serviceBayId(),
              state.technicianId(),
              zone,
              AppointmentStatus.CANCELLED.name()));
    }
  }

  /**
   * Why the conditional UPDATE matched no row, read after it: gone (404), changed by someone else
   * (412, or a restart when the caller gave no If-Match and attempts remain), or — status and
   * version unchanged — the no-show grace by the database clock (422).
   */
  private RuntimeException diagnose(Prepared p, boolean mayRestart) {
    UUID id = p.state().id();
    Optional<AppointmentState> current =
        appointments.findState(id).filter(s -> s.dealershipId().equals(p.state().dealershipId()));
    if (current.isEmpty()) {
      return ChangeDiagnosis.notFound(id);
    }
    long currentVersion = current.get().version();
    if (p.expected().isEmpty() && currentVersion != p.state().version() && mayRestart) {
      return new MovedUnderUsException();
    }
    if (currentVersion == p.state().version() && p.action() == Action.NO_SHOW) {
      return noShowTooEarly(p.dealership(), current.get());
    }
    return ChangeDiagnosis.stale(id);
  }

  private static BusinessRuleException noShowTooEarly(DealershipView d, AppointmentState state) {
    Instant from = state.period().start().plus(NO_SHOW_GRACE);
    return new BusinessRuleException(
        ProblemCodes.NO_SHOW_TOO_EARLY,
        "No-show can be recorded from "
            + CLOCK.format(from.atZone(d.zoneId()))
            + " ("
            + d.zoneId().getId()
            + "), 30 minutes after the start.");
  }

  /** The refused RESUME: who holds the time, read after the rollback. */
  private SlotUnavailableException unavailable(Prepared p) {
    ReservationRow bay = p.resumeRows().get(0);
    ReservationRow technician = p.resumeRows().get(1);
    List<UUID> conflicts =
        reservations.conflictingAppointments(
            p.state().dealershipId(),
            List.of(technician.resourceId()),
            technician.period(),
            List.of(bay.resourceId()),
            bay.period());
    return new SlotUnavailableException(
        DispatcherResources.taken(p.dealership(), technician.period().start()),
        List.of(),
        DispatcherResources.others(conflicts, p.state().id()));
  }
}
