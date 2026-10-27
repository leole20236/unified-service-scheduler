package com.keyloop.scheduler.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keyloop.scheduler.availability.AvailabilityCalculator;
import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.ServiceTypeView;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.SlotUnavailableException;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.db.SqlStates;
import com.keyloop.scheduler.common.id.Ids;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.time.DbClock;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.customer.CustomerService;
import com.keyloop.scheduler.customer.VehicleView;
import com.keyloop.scheduler.outbox.OutboxWriter;
import com.keyloop.scheduler.query.AppointmentRecord;
import com.keyloop.scheduler.query.AppointmentView;
import com.keyloop.scheduler.query.AppointmentViewMapper;
import com.keyloop.scheduler.query.ServiceLine;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Books appointments on the direct path (spec 8.3): validate, rank candidate pairs from the
 * primary, then try up to three pairs, each in its own READ COMMITTED transaction. The database's
 * EXCLUDE constraint decides every race; a refused pair drops its taken resource and the next pair
 * is tried.
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
  private static final DateTimeFormatter CLOCK =
      DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

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

  /** Creates the service; {@code transactions} runs each attempt in a new transaction. */
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
      ObjectMapper json) {
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

    Occupancy occupied = occupancy.load(req.dealershipId(), rankingWindow(catalog, plan));
    List<ResourcePair> candidates =
        new ArrayList<>(allocation.rank(catalog, plan, occupied, pinnedBay, pinnedTechnician));
    Attempt attempt =
        new Attempt(caller, req, idempotencyKey, hash, customerId, vehicle, catalog, plan);
    int failures = 0;
    boolean deadlockRetried = false;
    while (failures < MAX_ATTEMPTS && !candidates.isEmpty()) {
      ResourcePair pair = candidates.get(0);
      Persisted persisted;
      try {
        persisted = transactions.execute(status -> persist(attempt, pair));
      } catch (ReservationConflictException e) {
        failures++;
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
    throw unavailable(channel, policy, now, catalog, plan, pinnedBay, pinnedTechnician);
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
      JobPlan plan) {}

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
        view,
        new AppointmentCommitted(
            id, dealershipId, plan.localDate(), a.request().holdId(), reserved));
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
                date -> dailyOccupancy.forDay(d.id(), date, d),
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
    return new SlotUnavailableException(
        "No qualified technician and bay are free at "
            + CLOCK.format(plan.localStart())
            + " on "
            + DAY.format(plan.localStart())
            + " ("
            + d.zoneId().getId()
            + ").",
        alternatives,
        conflicts);
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
