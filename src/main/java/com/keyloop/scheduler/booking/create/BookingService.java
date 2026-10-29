package com.keyloop.scheduler.booking.create;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyloop.scheduler.availability.AvailabilityCalculator;
import com.keyloop.scheduler.booking.core.AllocationService;
import com.keyloop.scheduler.booking.core.AppointmentRepository;
import com.keyloop.scheduler.booking.core.BookingConfig;
import com.keyloop.scheduler.booking.core.HoldGuard;
import com.keyloop.scheduler.booking.core.OccupancyRepository;
import com.keyloop.scheduler.booking.core.ReservationRepository;
import com.keyloop.scheduler.booking.domain.AppointmentStatus;
import com.keyloop.scheduler.booking.domain.BookingChannel;
import com.keyloop.scheduler.booking.domain.BookingPolicy;
import com.keyloop.scheduler.booking.domain.JobPlan;
import com.keyloop.scheduler.booking.domain.JobPlanner;
import com.keyloop.scheduler.booking.domain.NewAppointment;
import com.keyloop.scheduler.booking.domain.Occupancy;
import com.keyloop.scheduler.booking.domain.OccupancySource;
import com.keyloop.scheduler.booking.domain.ReservationConflictException;
import com.keyloop.scheduler.booking.domain.ReservationRow;
import com.keyloop.scheduler.booking.domain.ResourcePair;
import com.keyloop.scheduler.booking.domain.SlotMessages;
import com.keyloop.scheduler.booking.event.AppointmentBookedEvent;
import com.keyloop.scheduler.booking.event.AppointmentCommitted;
import com.keyloop.scheduler.booking.event.BookingListener;
import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.ServiceTypeView;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.api.BusinessRuleException;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.api.SlotUnavailableException;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.db.SqlStates;
import com.keyloop.scheduler.common.id.Ids;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.time.DbClock;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.customer.CustomerService;
import com.keyloop.scheduler.customer.VehicleView;
import com.keyloop.scheduler.hold.HoldPayload;
import com.keyloop.scheduler.hold.LedgerFailures;
import com.keyloop.scheduler.hold.SlotLedger;
import com.keyloop.scheduler.hold.SlotLedger.HoldLocation;
import com.keyloop.scheduler.hold.SlotLedger.StoredHold;
import com.keyloop.scheduler.outbox.OutboxWriter;
import com.keyloop.scheduler.query.AppointmentRecord;
import com.keyloop.scheduler.query.AppointmentView;
import com.keyloop.scheduler.query.AppointmentViewMapper;
import com.keyloop.scheduler.query.ServiceLine;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Books appointments (spec 8.3, 8.6). With a live, matching hold the held pair is tried once
 * (confirm path); otherwise candidate pairs are ranked from the primary and up to three are tried
 * (direct path). Each attempt is its own READ COMMITTED transaction and the database's EXCLUDE
 * constraint decides every race; a refused pair drops its taken resource and marks the slot ledger
 * of that day dirty.
 */
@Service
public class BookingService {

  static final int MAX_ATTEMPTS = 3;
  static final int MAX_ALTERNATIVES = 5;
  static final int CREATED = 201;

  private static final Logger LOG = LoggerFactory.getLogger(BookingService.class);
  private static final int MAX_KEY_LENGTH = 64;
  private static final String AGGREGATE = "Appointment";
  private static final String BOOKED_EVENT = "AppointmentBooked";

  private final CatalogService catalogs;
  private final CustomerService customers;
  private final AllocationService allocation;
  private final OccupancyRepository occupancy;
  private final OccupancySource dailyOccupancy;
  private final DbClock clock;
  private final TransactionOperations transactions;
  private final IdempotencyStore idempotency;
  private final AppointmentRepository appointments;
  private final ReservationRepository reservations;
  private final OutboxWriter outbox;
  private final BookingListener listener;
  private final ObjectMapper json;
  private final SlotLedger ledger;
  private final HoldGuard holds;

  /**
   * Creates the service; {@code transactions} runs each attempt in a new transaction, {@code
   * ledger} supplies holds and is told about conflicts.
   */
  public BookingService(
      CatalogService catalogs,
      CustomerService customers,
      AllocationService allocation,
      OccupancyRepository occupancy,
      OccupancySource dailyOccupancy,
      DbClock clock,
      @Qualifier(BookingConfig.BOOKING_TRANSACTIONS) TransactionOperations transactions,
      IdempotencyStore idempotency,
      AppointmentRepository appointments,
      ReservationRepository reservations,
      OutboxWriter outbox,
      BookingListener listener,
      ObjectMapper json,
      SlotLedger ledger) {
    this.catalogs = catalogs;
    this.customers = customers;
    this.allocation = allocation;
    this.occupancy = occupancy;
    this.dailyOccupancy = dailyOccupancy;
    this.clock = clock;
    this.transactions = transactions;
    this.idempotency = idempotency;
    this.appointments = appointments;
    this.reservations = reservations;
    this.outbox = outbox;
    this.listener = listener;
    this.json = json;
    this.ledger = ledger;
    this.holds = new HoldGuard(ledger);
  }

  /**
   * Books one appointment, or replays the stored response of an earlier request with the same
   * Idempotency-Key. Throws {@link SlotUnavailableException} when no candidate pair could be
   * reserved.
   */
  public BookingResult book(
      CallerContext caller, BookingChannel channel, BookingRequest req, String idempotencyKey) {
    requireKey(idempotencyKey);
    String hash = RequestHash.of(channel, req);
    Optional<StoredResponse> stored = idempotency.find(caller.userId(), idempotencyKey, hash);
    if (stored.isPresent()) {
      return replay(stored.get());
    }
    UUID customerId;
    VehicleView vehicle;
    if (channel == BookingChannel.CUSTOMER) {
      customerId = customers.currentCustomer(caller);
      vehicle = customers.requireOwnedVehicle(customerId, req.vehicleId());
    } else {
      customerId = req.customerId();
      vehicle = customers.requireVehicleOf(customerId, req.vehicleId());
    }
    ResourceCatalog catalog = catalogs.get(req.dealershipId());
    JobPlan plan = JobPlanner.plan(catalog, req.serviceTypeIds(), req.start());
    Optional<UUID> pinnedBay =
        pinned(
            req.serviceBayId(),
            catalog.bays().stream().map(BayView::id).toList(),
            "Service bay not found.");
    Optional<UUID> pinnedTechnician =
        pinned(
            req.technicianId(),
            catalog.technicians().stream().map(TechnicianView::id).toList(),
            "Technician not found.");
    BookingPolicy policy = channel.policy();
    Instant now = clock.now();
    policy.checkNew(plan, catalog.dealership(), now);

    Optional<StoredHold> hold = heldFor(caller, req, catalog, plan);
    List<ResourcePair> candidates;
    if (hold.isPresent()) {
      candidates = new ArrayList<>(List.of(hold.get().pair()));
    } else {
      Occupancy occupied = occupancy.load(req.dealershipId(), rankingWindow(catalog, plan));
      candidates =
          new ArrayList<>(allocation.rank(catalog, plan, occupied, pinnedBay, pinnedTechnician));
      if (channel == BookingChannel.DISPATCHER && !req.overrideHold()) {
        candidates = withoutHeld(catalog.dealership(), candidates, plan);
      }
    }
    Attempt attempt =
        new Attempt(
            caller,
            req,
            idempotencyKey,
            hash,
            customerId,
            vehicle,
            catalog,
            plan,
            hold.map(StoredHold::holdId).orElse(null));
    int failures = 0;
    boolean deadlockRetried = false;
    while (failures < MAX_ATTEMPTS && !candidates.isEmpty()) {
      ResourcePair pair = candidates.get(0);
      if (channel == BookingChannel.DISPATCHER && req.overrideHold()) {
        holds.clear(catalog.dealership(), targets(List.of(pair), plan), true);
      }
      Persisted persisted;
      try {
        persisted = transactions.execute(status -> persist(attempt, pair));
      } catch (ReservationConflictException e) {
        failures++;
        markDirty(req.dealershipId(), plan);
        UUID taken = e.resourceId();
        candidates.removeIf(p -> p.bayId().equals(taken) || p.technicianId().equals(taken));
        continue;
      } catch (DuplicateRequestException e) {
        return idempotency
            .find(caller.userId(), idempotencyKey, hash)
            .map(this::replay)
            .orElseThrow(IdempotencyStore::inProgress);
      } catch (RuntimeException e) {
        if (!deadlockRetried && SqlStates.has(e, SqlStates.DEADLOCK_DETECTED)) {
          deadlockRetried = true;
          continue;
        }
        throw e;
      }
      afterCommit(persisted.event());
      return new BookingResult(CREATED, persisted.view(), false);
    }
    throw unavailable(caller, channel, policy, now, catalog, plan, pinnedBay, pinnedTechnician);
  }

  /**
   * The caller's live hold named by the request, checked against it (spec 8.6 confirm): someone
   * else's hold is a 404, a hold for another dealership, start or service set a 422. A hold that is
   * gone (expired, flushed, Redis unreachable) yields empty: the direct path then runs and the
   * database still decides.
   */
  private Optional<StoredHold> heldFor(
      CallerContext caller, BookingRequest req, ResourceCatalog catalog, JobPlan plan) {
    if (req.holdId() == null) {
      return Optional.empty();
    }
    Optional<HoldLocation> location;
    Optional<StoredHold> hold;
    try {
      location = ledger.locate(req.holdId());
      hold = location.flatMap(l -> ledger.getHold(l.dealershipId(), l.date(), req.holdId()));
    } catch (RuntimeException e) {
      if (!LedgerFailures.isRedisFailure(e)) {
        throw e;
      }
      LOG.warn("Hold {} unreadable, booking on the direct path: {}", req.holdId(), e.toString());
      return Optional.empty();
    }
    if (hold.isEmpty()) {
      return Optional.empty();
    }
    StoredHold h = hold.get();
    if (!h.userId().equals(caller.userId())) {
      throw new NotFoundException("Hold not found.");
    }
    if (!location.get().dealershipId().equals(req.dealershipId())) {
      throw new BusinessRuleException(
          ProblemCodes.HOLD_MISMATCH, "Your hold is for another dealership.");
    }
    List<UUID> heldServices = HoldPayload.parse(h.payloadJson()).serviceTypeIds();
    if (!h.period().start().equals(plan.period().start())
        || !new HashSet<>(heldServices).equals(new HashSet<>(req.serviceTypeIds()))) {
      throw new BusinessRuleException(
          ProblemCodes.HOLD_MISMATCH,
          "Your hold is for "
              + describe(catalog, h.period().start(), heldServices)
              + "; this request asks for "
              + describe(catalog, plan.period().start(), req.serviceTypeIds())
              + ".");
    }
    return hold;
  }

  private static String describe(ResourceCatalog catalog, Instant start, List<UUID> services) {
    List<String> names = new ArrayList<>();
    for (UUID id : services) {
      catalog.services().stream()
          .filter(s -> s.id().equals(id))
          .findFirst()
          .ifPresent(s -> names.add(s.name()));
    }
    return SlotMessages.at(start.atZone(catalog.dealership().zoneId()).toLocalDateTime())
        + " with "
        + String.join(", ", names);
  }

  /**
   * Dispatcher bookings respect customers' live holds (spec 8.6): pairs touching a held resource
   * are dropped; when every candidate is held the booking is refused with 409 SLOT_HELD.
   */
  private List<ResourcePair> withoutHeld(
      DealershipView d, List<ResourcePair> candidates, JobPlan plan) {
    if (candidates.isEmpty()) {
      return candidates;
    }
    List<HoldGuard.Held> held = holds.find(d, targets(candidates, plan));
    if (held.isEmpty()) {
      return candidates;
    }
    Set<UUID> taken = new HashSet<>();
    held.forEach(h -> taken.addAll(h.resources()));
    List<ResourcePair> free =
        new ArrayList<>(
            candidates.stream()
                .filter(p -> !taken.contains(p.bayId()) && !taken.contains(p.technicianId()))
                .toList());
    if (free.isEmpty()) {
      throw holds.refusal(d, held);
    }
    return free;
  }

  /** The periods the pairs' technicians and bays would be occupied. */
  private static Map<UUID, TimeRange> targets(List<ResourcePair> pairs, JobPlan plan) {
    Map<UUID, TimeRange> targets = new LinkedHashMap<>();
    for (ResourcePair p : pairs) {
      targets.put(p.technicianId(), plan.period());
      targets.put(p.bayId(), plan.bayPeriod());
    }
    return targets;
  }

  /** A refused write means the ledger let a taken slot through: rebuild it before its next use. */
  private void markDirty(UUID dealershipId, JobPlan plan) {
    try {
      ledger.markDirty(dealershipId, plan.localDate());
    } catch (RuntimeException e) {
      LOG.warn("Could not mark ledger of {} dirty: {}", dealershipId, e.toString());
    }
  }

  /** Everything one attempt needs besides the pair. */
  private record Attempt(
      CallerContext caller,
      BookingRequest request,
      String key,
      String hash,
      UUID customerId,
      VehicleView vehicle,
      ResourceCatalog catalog,
      JobPlan plan,
      UUID holdId) {}

  /** What a committed attempt produced. */
  private record Persisted(AppointmentView view, AppointmentCommitted event) {}

  /** One transaction: key, appointment, reservations (by resource id), outbox, stored response. */
  private Persisted persist(Attempt a, ResourcePair pair) {
    UUID userId = a.caller().userId();
    UUID dealershipId = a.request().dealershipId();
    JobPlan plan = a.plan();
    idempotency.begin(userId, a.key(), a.hash());
    UUID id = Ids.newId();
    appointments.insert(
        new NewAppointment(
            id,
            dealershipId,
            a.customerId(),
            a.vehicle().id(),
            pair.bayId(),
            pair.technicianId(),
            plan.period(),
            a.request().customerNotes(),
            userId,
            plan.services()));
    Map<UUID, ReservationRow> reserved =
        reservations.insertAll(
            id,
            dealershipId,
            List.of(
                new ReservationRow(pair.bayId(), ReservationRow.BAY, plan.bayPeriod()),
                new ReservationRow(pair.technicianId(), ReservationRow.TECHNICIAN, plan.period())));
    DealershipView d = a.catalog().dealership();
    outbox.append(
        AGGREGATE,
        id,
        dealershipId,
        BOOKED_EVENT,
        0,
        new AppointmentBookedEvent(
            id,
            a.customerId(),
            a.vehicle().id(),
            plan.services().stream().map(ServiceTypeView::code).toList(),
            pair.bayId(),
            pair.technicianId(),
            plan.period().start(),
            plan.period().end(),
            d.zoneId().getId(),
            AppointmentStatus.BOOKED.name()));
    AppointmentView view = AppointmentViewMapper.toView(record(id, a, pair));
    idempotency.complete(userId, a.key(), CREATED, view);
    return new Persisted(
        view, new AppointmentCommitted(id, dealershipId, plan.localDate(), a.holdId(), reserved));
  }

  private static AppointmentRecord record(UUID id, Attempt a, ResourcePair pair) {
    ResourceCatalog catalog = a.catalog();
    DealershipView d = catalog.dealership();
    VehicleView v = a.vehicle();
    List<ServiceLine> lines = new ArrayList<>();
    for (ServiceTypeView s : a.plan().services()) {
      lines.add(new ServiceLine(lines.size() + 1, s.code(), s.name(), s.durationMinutes()));
    }
    String bayName =
        catalog.bays().stream()
            .filter(b -> b.id().equals(pair.bayId()))
            .findFirst()
            .orElseThrow()
            .name();
    String technicianName =
        catalog.technicians().stream()
            .filter(t -> t.id().equals(pair.technicianId()))
            .findFirst()
            .orElseThrow()
            .displayName();
    TimeRange period = a.plan().period();
    return new AppointmentRecord(
        id,
        AppointmentStatus.BOOKED.name(),
        0,
        d.id(),
        d.name(),
        d.zoneId().getId(),
        v.id(),
        v.registration(),
        v.make(),
        v.model(),
        OffsetDateTime.ofInstant(period.start(), ZoneOffset.UTC),
        OffsetDateTime.ofInstant(period.end(), ZoneOffset.UTC),
        pair.bayId(),
        bayName,
        pair.technicianId(),
        technicianName,
        lines);
  }

  private void afterCommit(AppointmentCommitted event) {
    try {
      listener.afterCommit(event);
    } catch (RuntimeException e) {
      LOG.warn(
          "Booking listener failed for appointment {}: {}", event.appointmentId(), e.toString());
    }
  }

  private BookingResult replay(StoredResponse stored) {
    try {
      return new BookingResult(
          stored.status(), json.readValue(stored.body(), AppointmentView.class), true);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Stored idempotent response is unreadable", e);
    }
  }

  private SlotUnavailableException unavailable(
      CallerContext caller,
      BookingChannel channel,
      BookingPolicy policy,
      Instant now,
      ResourceCatalog catalog,
      JobPlan plan,
      Optional<UUID> pinnedBay,
      Optional<UUID> pinnedTechnician) {
    DealershipView d = catalog.dealership();
    List<Instant> alternatives =
        AvailabilityCalculator.alternatives(
                catalog,
                plan,
                date -> dailyOccupancy.forDay(d.id(), date, d, caller.userId()),
                policy.earliestStart(now),
                policy.latestStart(now),
                MAX_ALTERNATIVES)
            .stream()
            .map(OffsetDateTime::toInstant)
            .toList();
    List<UUID> conflicts = null;
    if (channel == BookingChannel.DISPATCHER) {
      List<UUID> technicians =
          pinnedTechnician
              .map(List::of)
              .orElseGet(
                  () ->
                      catalog.technicians().stream()
                          .filter(t -> t.skills().containsAll(plan.requiredSkills()))
                          .map(TechnicianView::id)
                          .toList());
      List<UUID> bays =
          pinnedBay
              .map(List::of)
              .orElseGet(() -> catalog.bays().stream().map(BayView::id).toList());
      conflicts =
          reservations.conflictingAppointments(
              d.id(), technicians, plan.period(), bays, plan.bayPeriod());
    }
    return new SlotUnavailableException(SlotMessages.unavailable(plan, d), alternatives, conflicts);
  }

  /** The local day (plus any overrun of the bay period) on the primary, for ranking. */
  private static TimeRange rankingWindow(ResourceCatalog catalog, JobPlan plan) {
    DealershipView d = catalog.dealership();
    Instant dayStart = plan.localDate().atStartOfDay(d.zoneId()).toInstant();
    Instant dayEnd = plan.localDate().plusDays(1).atStartOfDay(d.zoneId()).toInstant();
    return new TimeRange(dayStart, Collections.max(List.of(dayEnd, plan.bayPeriod().end())));
  }

  private static Optional<UUID> pinned(UUID id, List<UUID> active, String notFound) {
    if (id == null) {
      return Optional.empty();
    }
    if (!active.contains(id)) {
      throw new NotFoundException(notFound);
    }
    return Optional.of(id);
  }

  private static void requireKey(String key) {
    if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
      throw new ValidationException(
          "1 field is invalid.",
          List.of(Map.of("field", "Idempotency-Key", "message", "must be 1 to 64 characters")));
    }
  }
}
