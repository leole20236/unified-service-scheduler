package com.keyloop.scheduler.hold;

import com.keyloop.scheduler.booking.Occupancy;
import com.keyloop.scheduler.booking.OccupancyRepository;
import com.keyloop.scheduler.booking.ReservationRow;
import com.keyloop.scheduler.booking.ReservedInterval;
import com.keyloop.scheduler.booking.ResourcePair;
import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.id.Ids;
import com.keyloop.scheduler.common.time.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The Redis slot ledger (spec 8.6): per dealership-day, every confirmed reservation (mirrored from
 * the primary) and every live hold, per bay and technician. All mutations of a dealership-day run
 * as Lua scripts over keys sharing one hash tag, so each is atomic; hold expiry uses Redis {@code
 * TIME}. The ledger only lets collisions fail fast: PostgreSQL's EXCLUDE constraint still decides
 * every booking.
 */
@Component
public class SlotLedger {

  /** A hold just granted on a pair. */
  public record HeldPair(UUID holdId, ResourcePair pair, Instant expiresAt) {}

  /** A live hold as stored in Redis. */
  public record StoredHold(
      UUID holdId,
      UUID userId,
      ResourcePair pair,
      TimeRange period,
      TimeRange bayPeriod,
      String payloadJson,
      Instant expiresAt) {}

  /** The dealership-day a hold id belongs to. */
  public record HoldLocation(UUID dealershipId, LocalDate date) {}

  static final Duration MARKER_TTL = Duration.ofMinutes(10);
  private static final Logger LOG = LoggerFactory.getLogger(SlotLedger.class);
  private static final int LOAD_ATTEMPTS = 3;
  private static final String CONFIRMED = "R";
  private static final String HELD = "H";

  private final StringRedisTemplate redis;
  private final OccupancyRepository primary;
  private final CatalogService catalogs;
  private final Duration holdTtl;
  private final Duration ledgerTtl;
  private final Map<LedgerKeys, CompletableFuture<Void>> loading = new ConcurrentHashMap<>();

  @SuppressWarnings("rawtypes")
  private final RedisScript<List> acquire = script("acquire_hold", List.class);

  @SuppressWarnings("rawtypes")
  private final RedisScript<List> read = script("read_ledger", List.class);

  private final RedisScript<Long> confirm = script("confirm_hold", Long.class);
  private final RedisScript<Long> release = script("release_hold", Long.class);
  private final RedisScript<Long> apply = script("apply_reservations", Long.class);
  private final RedisScript<Long> load = script("load_ledger", Long.class);
  private final RedisScript<Long> dirty = script("mark_dirty", Long.class);

  /** Creates the ledger; {@code app.hold.ttl} is the hold lifetime (10 minutes by default). */
  public SlotLedger(
      StringRedisTemplate redis,
      OccupancyRepository primary,
      CatalogService catalogs,
      @Value("${app.hold.ttl:10m}") Duration holdTtl) {
    this.redis = redis;
    this.primary = primary;
    this.catalogs = catalogs;
    this.holdTtl = holdTtl;
    // Interval sets outlive both the marker and any hold placed in them.
    this.ledgerTtl = (holdTtl.compareTo(MARKER_TTL) > 0 ? holdTtl : MARKER_TTL).multipliedBy(2);
  }

  private static <T> RedisScript<T> script(String name, Class<T> type) {
    DefaultRedisScript<T> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("redis/" + name + ".lua"));
    script.setResultType(type);
    return script;
  }

  /**
   * The span of one dealership-local date the ledger mirrors: the whole local day plus the bay
   * buffer, which covers every customer job (and its bay period) of that date.
   */
  public static TimeRange dayWindow(DealershipView dealership, LocalDate date) {
    return new TimeRange(
        date.atStartOfDay(dealership.zoneId()).toInstant(),
        date.plusDays(1)
            .atStartOfDay(dealership.zoneId())
            .toInstant()
            .plus(Duration.ofMinutes(dealership.bayBufferMinutes())));
  }

  /** Redis server time: the one clock of hold expiry. */
  public Instant now() {
    Long millis = redis.execute((RedisCallback<Long>) c -> c.serverCommands().time());
    return Instant.ofEpochMilli(millis);
  }

  /**
   * Rebuilds the day from the primary when its marker is missing. Concurrent callers in this JVM
   * share one rebuild; a rebuild overtaken by a write-through is retried, and when every attempt is
   * overtaken {@link LedgerUnavailableException} is thrown: an unmarked ledger is never used.
   */
  public void ensureLoaded(UUID dealershipId, LocalDate date, TimeRange dayWindow) {
    LedgerKeys keys = LedgerKeys.of(dealershipId, date);
    if (Boolean.TRUE.equals(redis.hasKey(keys.loaded()))) {
      return;
    }
    CompletableFuture<Void> mine = new CompletableFuture<>();
    CompletableFuture<Void> running = loading.putIfAbsent(keys, mine);
    if (running != null) {
      await(running);
      return;
    }
    try {
      rebuild(keys, dayWindow);
      mine.complete(null);
    } catch (RuntimeException e) {
      mine.completeExceptionally(e);
      throw e;
    } finally {
      loading.remove(keys, mine);
    }
  }

  private static void await(CompletableFuture<Void> running) {
    try {
      running.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while the ledger was loading", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(e.getCause());
    }
  }

  private void rebuild(LedgerKeys keys, TimeRange dayWindow) {
    ResourceCatalog catalog = catalogs.get(keys.dealershipId());
    for (int attempt = 1; attempt <= LOAD_ATTEMPTS; attempt++) {
      String generation =
          Optional.ofNullable(redis.opsForValue().get(keys.generation())).orElse("0");
      List<ReservedInterval> rows = primary.intervals(keys.dealershipId(), dayWindow);
      Set<UUID> resources = new LinkedHashSet<>(resourceIds(catalog));
      rows.forEach(r -> resources.add(r.resourceId()));
      List<String> args = new ArrayList<>();
      args.add(keys.tag());
      args.add(generation);
      args.add(Long.toString(MARKER_TTL.toMillis()));
      args.add(Long.toString(ledgerTtl.toMillis()));
      args.add(Integer.toString(resources.size()));
      resources.forEach(id -> args.add(id.toString()));
      for (ReservedInterval row : rows) {
        addConfirmed(args, row.reservationId(), row.resourceId(), row.period());
      }
      Long loaded = redis.execute(load, List.of(keys.loaded(), keys.generation()), args.toArray());
      if (Long.valueOf(1).equals(loaded)) {
        return;
      }
    }
    LOG.info(
        "Ledger {} not rebuilt: overtaken by writes {} times; not used for this call",
        keys.tag(),
        LOAD_ATTEMPTS);
    throw new LedgerUnavailableException(
        "Ledger " + keys.tag() + " could not be rebuilt from the primary");
  }

  private static List<UUID> resourceIds(ResourceCatalog catalog) {
    List<UUID> ids = new ArrayList<>();
    catalog.bays().stream().map(BayView::id).forEach(ids::add);
    catalog.technicians().stream().map(TechnicianView::id).forEach(ids::add);
    return ids;
  }

  private static void addConfirmed(
      List<String> args, UUID reservationId, UUID resourceId, TimeRange period) {
    long start = period.start().toEpochMilli();
    args.add(resourceId.toString());
    args.add(CONFIRMED + ":" + reservationId + ":" + start + ":" + period.end().toEpochMilli());
    args.add(Long.toString(start));
  }

  /**
   * Holds the first pair, in rank order, whose technician is free for {@code period} and whose bay
   * is free for {@code bayPeriod}; the user's previous hold of this day is released first. Writes
   * {@code holdref} after a grant.
   */
  public Optional<HeldPair> acquire(
      UUID dealershipId,
      LocalDate date,
      UUID userId,
      List<ResourcePair> rankedPairs,
      TimeRange period,
      TimeRange bayPeriod,
      String payloadJson) {
    return acquire(
        dealershipId, date, userId, rankedPairs, period, bayPeriod, payloadJson, Set.of());
  }

  /**
   * As above, but confirmed intervals of {@code ignoredReservations} (the caller's own appointment
   * being rescheduled) do not count as busy.
   */
  @SuppressWarnings("unchecked")
  public Optional<HeldPair> acquire(
      UUID dealershipId,
      LocalDate date,
      UUID userId,
      List<ResourcePair> rankedPairs,
      TimeRange period,
      TimeRange bayPeriod,
      String payloadJson,
      Set<UUID> ignoredReservations) {
    LedgerKeys keys = LedgerKeys.of(dealershipId, date);
    UUID holdId = Ids.newId();
    List<String> args = new ArrayList<>();
    args.add(keys.tag());
    args.add(userId.toString());
    args.add(holdId.toString());
    args.add(Long.toString(period.start().toEpochMilli()));
    args.add(Long.toString(period.end().toEpochMilli()));
    args.add(Long.toString(bayPeriod.start().toEpochMilli()));
    args.add(Long.toString(bayPeriod.end().toEpochMilli()));
    args.add(Long.toString(holdTtl.toMillis()));
    args.add(Long.toString(ledgerTtl.toMillis()));
    args.add(payloadJson);
    args.add(Integer.toString(ignoredReservations.size()));
    ignoredReservations.forEach(id -> args.add(id.toString()));
    for (ResourcePair pair : rankedPairs) {
      args.add(pair.bayId().toString());
      args.add(pair.technicianId().toString());
    }
    Set<String> scriptKeys = new LinkedHashSet<>(List.of(keys.userHold(userId), keys.hold(holdId)));
    for (ResourcePair pair : rankedPairs) {
      scriptKeys.add(keys.ledger(pair.bayId()));
      scriptKeys.add(keys.ledger(pair.technicianId()));
    }
    List<Object> result = redis.execute(acquire, List.copyOf(scriptKeys), args.toArray());
    String previous = (String) result.get(4);
    if (!previous.isEmpty()) {
      redis.delete(LedgerKeys.holdRef(UUID.fromString(previous)));
    }
    if (((String) result.get(0)).isEmpty()) {
      return Optional.empty();
    }
    Instant expiresAt = Instant.ofEpochMilli(Long.parseLong((String) result.get(3)));
    redis.opsForValue().set(LedgerKeys.holdRef(holdId), keys.holdRefValue(), holdTtl);
    return Optional.of(
        new HeldPair(
            holdId,
            new ResourcePair(
                UUID.fromString((String) result.get(1)), UUID.fromString((String) result.get(2))),
            expiresAt));
  }

  /** Where a hold id lives, if its reference has not lapsed. */
  public Optional<HoldLocation> locate(UUID holdId) {
    return Optional.ofNullable(redis.opsForValue().get(LedgerKeys.holdRef(holdId)))
        .map(LedgerKeys::parseHoldRef);
  }

  /** The live hold, if it exists (an expired hold's hash is gone with its TTL). */
  public Optional<StoredHold> getHold(UUID dealershipId, LocalDate date, UUID holdId) {
    Map<Object, Object> h =
        redis.opsForHash().entries(LedgerKeys.of(dealershipId, date).hold(holdId));
    if (h.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new StoredHold(
            holdId,
            UUID.fromString((String) h.get("userId")),
            new ResourcePair(
                UUID.fromString((String) h.get("bayId")),
                UUID.fromString((String) h.get("technicianId"))),
            range(h.get("start"), h.get("end")),
            range(h.get("bayStart"), h.get("bayEnd")),
            (String) h.get("payload"),
            Instant.ofEpochMilli(Long.parseLong((String) h.get("expiresAt")))));
  }

  private static TimeRange range(Object start, Object end) {
    return new TimeRange(
        Instant.ofEpochMilli(Long.parseLong((String) start)),
        Instant.ofEpochMilli(Long.parseLong((String) end)));
  }

  /**
   * After the booking committed: the hold's intervals become confirmed intervals, keyed by the new
   * reservation ids, and the hold is deleted.
   */
  public void confirm(
      UUID dealershipId, LocalDate date, UUID holdId, Map<UUID, ReservationRow> committed) {
    LedgerKeys keys = LedgerKeys.of(dealershipId, date);
    List<String> args = new ArrayList<>();
    args.add(keys.tag());
    args.add(holdId.toString());
    args.add(Long.toString(ledgerTtl.toMillis()));
    committed.forEach((id, row) -> addConfirmed(args, id, row.resourceId(), row.period()));
    Set<String> scriptKeys = new LinkedHashSet<>(List.of(keys.hold(holdId), keys.generation()));
    committed.values().forEach(row -> scriptKeys.add(keys.ledger(row.resourceId())));
    redis.execute(confirm, List.copyOf(scriptKeys), args.toArray());
    redis.delete(LedgerKeys.holdRef(holdId));
  }

  /** Releases the hold if {@code userId} owns it; someone else's hold is never touched. */
  public void release(UUID dealershipId, LocalDate date, UUID holdId, UUID userId) {
    LedgerKeys keys = LedgerKeys.of(dealershipId, date);
    Long released =
        redis.execute(
            release, List.of(keys.hold(holdId)), keys.tag(), holdId.toString(), userId.toString());
    if (Long.valueOf(1).equals(released)) {
      redis.delete(LedgerKeys.holdRef(holdId));
    }
  }

  /** Write-through of a committed change: removed reservations go, added ones appear. */
  public void applyCommitted(
      UUID dealershipId,
      LocalDate date,
      List<UUID> removedReservationIds,
      Map<UUID, ReservationRow> added) {
    LedgerKeys keys = LedgerKeys.of(dealershipId, date);
    List<UUID> scan =
        removedReservationIds.isEmpty() ? List.of() : resourceIds(catalogs.get(dealershipId));
    List<String> args = new ArrayList<>();
    args.add(keys.tag());
    args.add(Long.toString(ledgerTtl.toMillis()));
    args.add(Integer.toString(scan.size()));
    scan.forEach(id -> args.add(id.toString()));
    args.add(Integer.toString(removedReservationIds.size()));
    removedReservationIds.forEach(id -> args.add(id.toString()));
    added.forEach((id, row) -> addConfirmed(args, id, row.resourceId(), row.period()));
    Set<String> scriptKeys = new LinkedHashSet<>(List.of(keys.generation()));
    scan.forEach(id -> scriptKeys.add(keys.ledger(id)));
    added.values().forEach(row -> scriptKeys.add(keys.ledger(row.resourceId())));
    redis.execute(apply, List.copyOf(scriptKeys), args.toArray());
  }

  /** Forces a rebuild of the day from the primary before its next use (after a 23P01). */
  public void markDirty(UUID dealershipId, LocalDate date) {
    LedgerKeys keys = LedgerKeys.of(dealershipId, date);
    redis.execute(dirty, List.of(keys.loaded()));
  }

  /**
   * Occupied intervals per resource: confirmed ones always, live holds when {@code includeHolds},
   * except the hold of {@code ignoreUserHolds} (a viewer still sees the slot they hold).
   */
  public Occupancy occupancy(
      UUID dealershipId, LocalDate date, boolean includeHolds, UUID ignoreUserHolds) {
    return occupancy(dealershipId, date, includeHolds, ignoreUserHolds, Set.of());
  }

  /** As above, also leaving out the confirmed intervals of {@code ignoredReservations}. */
  public Occupancy occupancy(
      UUID dealershipId,
      LocalDate date,
      boolean includeHolds,
      UUID ignoreUserHolds,
      Set<UUID> ignoredReservations) {
    List<UUID> resources = resourceIds(catalogs.get(dealershipId));
    Map<UUID, List<Interval>> intervals =
        readIntervals(dealershipId, date, resources, ignoreUserHolds);
    Map<UUID, List<TimeRange>> byResource = new HashMap<>();
    intervals.forEach(
        (resource, list) ->
            byResource.put(
                resource,
                list.stream()
                    .filter(i -> includeHolds || !i.held())
                    .filter(i -> i.held() || !ignoredReservations.contains(i.id()))
                    .map(Interval::range)
                    .toList()));
    return new Occupancy(byResource);
  }

  /** Ids of live holds on the resource that overlap {@code r}. */
  public List<UUID> heldBy(UUID dealershipId, LocalDate date, UUID resourceId, TimeRange r) {
    return readIntervals(dealershipId, date, List.of(resourceId), null)
        .getOrDefault(resourceId, List.of())
        .stream()
        .filter(i -> i.held() && i.range().overlaps(r))
        .map(Interval::id)
        .toList();
  }

  /**
   * Ids of live holds overlapping each resource's range, in one consistent read of the day; only
   * resources with at least one such hold are present.
   */
  public Map<UUID, List<UUID>> heldBy(
      UUID dealershipId, LocalDate date, Map<UUID, TimeRange> ranges) {
    Map<UUID, List<UUID>> held = new HashMap<>();
    readIntervals(dealershipId, date, ranges.keySet(), null)
        .forEach(
            (resource, intervals) -> {
              TimeRange r = ranges.get(resource);
              List<UUID> ids =
                  intervals.stream()
                      .filter(i -> i.held() && i.range().overlaps(r))
                      .map(Interval::id)
                      .toList();
              if (!ids.isEmpty()) {
                held.put(resource, ids);
              }
            });
    return held;
  }

  /** One live interval read from a ledger set. */
  private record Interval(UUID id, boolean held, TimeRange range) {}

  @SuppressWarnings("unchecked")
  private Map<UUID, List<Interval>> readIntervals(
      UUID dealershipId, LocalDate date, Collection<UUID> resources, UUID viewer) {
    LedgerKeys keys = LedgerKeys.of(dealershipId, date);
    List<String> args = new ArrayList<>();
    args.add(keys.tag());
    args.add(viewer == null ? "" : viewer.toString());
    resources.forEach(id -> args.add(id.toString()));
    List<String> scriptKeys = new ArrayList<>(List.of(keys.generation()));
    if (viewer != null) {
      scriptKeys.add(keys.userHold(viewer));
    }
    resources.forEach(id -> scriptKeys.add(keys.ledger(id)));
    List<Object> result = redis.execute(read, scriptKeys, args.toArray());
    long now = Long.parseLong((String) result.get(0));
    String own = (String) result.get(1);
    Map<UUID, List<Interval>> out = new HashMap<>();
    int index = 2;
    for (UUID resource : resources) {
      List<Interval> live = new ArrayList<>();
      for (Object raw : (List<Object>) result.get(index++)) {
        String[] f = ((String) raw).split(":");
        boolean held = HELD.equals(f[0]);
        if (held && (Long.parseLong(f[4]) <= now || f[1].equals(own))) {
          continue;
        }
        live.add(
            new Interval(
                UUID.fromString(f[1]),
                held,
                new TimeRange(
                    Instant.ofEpochMilli(Long.parseLong(f[2])),
                    Instant.ofEpochMilli(Long.parseLong(f[3])))));
      }
      out.put(resource, live);
    }
    return out;
  }
}
