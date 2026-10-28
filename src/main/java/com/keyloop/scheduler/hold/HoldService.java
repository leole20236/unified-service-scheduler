package com.keyloop.scheduler.hold;

import com.keyloop.scheduler.availability.AvailabilityCalculator;
import com.keyloop.scheduler.booking.AllocationService;
import com.keyloop.scheduler.booking.AppointmentRepository;
import com.keyloop.scheduler.booking.AppointmentState;
import com.keyloop.scheduler.booking.AppointmentStatus;
import com.keyloop.scheduler.booking.BookingChannel;
import com.keyloop.scheduler.booking.BookingPolicy;
import com.keyloop.scheduler.booking.JobPlan;
import com.keyloop.scheduler.booking.JobPlanner;
import com.keyloop.scheduler.booking.Occupancy;
import com.keyloop.scheduler.booking.ReservationRepository;
import com.keyloop.scheduler.booking.ResourcePair;
import com.keyloop.scheduler.booking.SlotMessages;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.common.api.BusinessRuleException;
import com.keyloop.scheduler.common.api.HoldsUnavailableException;
import com.keyloop.scheduler.common.api.InvalidTransitionException;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.api.SlotUnavailableException;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.customer.CustomerService;
import com.keyloop.scheduler.hold.SlotLedger.HeldPair;
import com.keyloop.scheduler.hold.SlotLedger.HoldLocation;
import com.keyloop.scheduler.hold.SlotLedger.StoredHold;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;

/**
 * Airline-style slot holds (spec 8.6). Granting or refusing a hold never touches the primary
 * database: the catalog comes from its cache, vehicle ownership from the replica, time from Redis
 * and occupancy from the slot ledger. Any Redis failure is a 503 HOLDS_UNAVAILABLE; customers can
 * still book on the direct path.
 */
@Service
public class HoldService {

  static final int MAX_ALTERNATIVES = 5;
  private static final Logger LOG = LoggerFactory.getLogger(HoldService.class);
  private static final String UNAVAILABLE =
      "Holds are unavailable right now; you can still book directly.";

  private final SlotLedger ledger;
  private final AllocationService allocation;
  private final CatalogService catalogs;
  private final CustomerService customers;
  private final AppointmentRepository appointments;
  private final ReservationRepository reservations;

  /** Creates the service. */
  public HoldService(
      SlotLedger ledger,
      AllocationService allocation,
      CatalogService catalogs,
      CustomerService customers,
      AppointmentRepository appointments,
      ReservationRepository reservations) {
    this.appointments = appointments;
    this.reservations = reservations;
    this.ledger = ledger;
    this.allocation = allocation;
    this.catalogs = catalogs;
    this.customers = customers;
  }

  /**
   * Validates like a customer booking (spec 6.3 rules 1-4) and holds the best free pair, releasing
   * the caller's previous hold of that dealership-day. No pair ⇒ {@link SlotUnavailableException}
   * with alternatives from the ledger.
   */
  public HoldView create(CallerContext caller, HoldRequest req) {
    customers
        .findMyVehicle(caller, req.vehicleId())
        .orElseGet(() -> customers.getMyVehicle(caller, req.vehicleId()));
    ResourceCatalog catalog = catalogs.get(req.dealershipId());
    JobPlan plan = JobPlanner.plan(catalog, req.serviceTypeIds(), req.start());
    DealershipView d = catalog.dealership();
    BookingPolicy policy = BookingChannel.CUSTOMER.policy();
    Set<UUID> own = ownReservations(caller, req, d);
    return redis(
        () -> {
          Instant now = ledger.now();
          policy.checkNew(plan, d, now);
          LocalDate date = plan.localDate();
          Occupancy occupied = occupancy(d, date, caller.userId(), own);
          List<ResourcePair> ranked =
              allocation.rank(catalog, plan, occupied, Optional.empty(), Optional.empty());
          Optional<HeldPair> held =
              ledger.acquire(
                  d.id(),
                  date,
                  caller.userId(),
                  ranked,
                  plan.period(),
                  plan.bayPeriod(),
                  HoldPayload.of(req.vehicleId(), req.serviceTypeIds(), req.rescheduleOf())
                      .toJson(),
                  own);
          if (held.isEmpty()) {
            List<Instant> alternatives =
                AvailabilityCalculator.alternatives(
                        catalog,
                        plan,
                        day -> occupancy(d, day, caller.userId(), own),
                        policy.earliestStart(now),
                        policy.latestStart(now),
                        MAX_ALTERNATIVES)
                    .stream()
                    .map(OffsetDateTime::toInstant)
                    .toList();
            throw new SlotUnavailableException(
                SlotMessages.unavailable(plan, d), alternatives, null);
          }
          return new HoldView(
              held.get().holdId(),
              utc(held.get().expiresAt()),
              utc(plan.period().start()),
              utc(plan.period().end()));
        });
  }

  /** The caller's live hold; missing, expired or someone else's ⇒ 404. */
  public HoldResource get(CallerContext caller, UUID holdId) {
    LocatedHold located =
        redis(() -> owned(caller, holdId))
            .orElseThrow(() -> new NotFoundException("Hold not found."));
    StoredHold hold = located.hold();
    return new HoldResource(
        holdId,
        located.dealershipId(),
        HoldPayload.parse(hold.payloadJson()).serviceTypeIds(),
        utc(hold.period().start()),
        utc(hold.period().end()),
        utc(hold.expiresAt()));
  }

  /** Releases the caller's hold; unknown, expired or someone else's holds are left alone. */
  public void release(CallerContext caller, UUID holdId) {
    redis(
        () -> {
          ledger
              .locate(holdId)
              .ifPresent(l -> ledger.release(l.dealershipId(), l.date(), holdId, caller.userId()));
          return null;
        });
  }

  private Optional<LocatedHold> owned(CallerContext caller, UUID holdId) {
    Optional<HoldLocation> location = ledger.locate(holdId);
    return location
        .flatMap(l -> ledger.getHold(l.dealershipId(), l.date(), holdId))
        .filter(h -> h.userId().equals(caller.userId()))
        .map(h -> new LocatedHold(location.get().dealershipId(), h));
  }

  /** A stored hold with the dealership it was found under. */
  private record LocatedHold(UUID dealershipId, StoredHold hold) {}

  private Occupancy occupancy(DealershipView d, LocalDate date, UUID viewer, Set<UUID> own) {
    ledger.ensureLoaded(d.id(), date, SlotLedger.dayWindow(d, date));
    return own.isEmpty()
        ? ledger.occupancy(d.id(), date, true, viewer)
        : ledger.occupancy(d.id(), date, true, viewer, own);
  }

  /**
   * For a hold taken to reschedule: the reservation ids of the caller's own BOOKED appointment at
   * this dealership, whose current slot must not count as taken. Someone else's appointment is a
   * 404. Empty for an ordinary hold.
   */
  private Set<UUID> ownReservations(CallerContext caller, HoldRequest req, DealershipView d) {
    if (req.rescheduleOf() == null) {
      return Set.of();
    }
    UUID customerId = customers.currentCustomer(caller);
    AppointmentState state =
        appointments
            .findState(req.rescheduleOf())
            .filter(s -> s.customerId().equals(customerId))
            .orElseThrow(
                () ->
                    new NotFoundException("Appointment " + req.rescheduleOf() + " was not found."));
    if (state.status() != AppointmentStatus.BOOKED) {
      throw new InvalidTransitionException(
          "Only booked appointments can be changed here; this one is " + state.status() + ".");
    }
    if (!state.dealershipId().equals(d.id())) {
      throw new BusinessRuleException(
          ProblemCodes.HOLD_MISMATCH, "That appointment is at another dealership.");
    }
    return Set.copyOf(reservations.activeIds(state.id()));
  }

  /**
   * Runs a ledger operation; any Redis failure becomes a 503. A command timeout is retried once
   * first: a reply lost in a latency spike is not an outage, and every operation here may safely
   * run twice (a repeated acquire releases the caller's first hold before granting again).
   */
  private static <T> T redis(Supplier<T> call) {
    try {
      try {
        return call.get();
      } catch (QueryTimeoutException e) {
        LOG.info("Slot ledger timed out, retrying once: {}", e.toString());
        return call.get();
      }
    } catch (RuntimeException e) {
      if (!LedgerFailures.isRedisFailure(e)) {
        throw e;
      }
      LOG.warn("Slot ledger unavailable: {}", e.toString());
      throw new HoldsUnavailableException(UNAVAILABLE);
    }
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
