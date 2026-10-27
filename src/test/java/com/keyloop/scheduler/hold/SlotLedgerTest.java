package com.keyloop.scheduler.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.booking.OccupancyRepository;
import com.keyloop.scheduler.booking.ReservationRow;
import com.keyloop.scheduler.booking.ResourcePair;
import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.CatalogService;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/** Script routing and rebuild give-up of the ledger, against a mocked Redis template. */
class SlotLedgerTest {

  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
  private static final LedgerKeys KEYS = LedgerKeys.of(Fixtures.RIVERSIDE, MONDAY);
  private static final TimeRange NINE =
      new TimeRange(Instant.parse("2026-11-02T09:00:00Z"), Instant.parse("2026-11-02T09:45:00Z"));

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  @SuppressWarnings("unchecked")
  private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);

  private final OccupancyRepository primary = mock(OccupancyRepository.class);
  private final CatalogService catalogs = mock(CatalogService.class);
  private final SlotLedger ledger =
      new SlotLedger(redis, primary, catalogs, Duration.ofMinutes(10));
  private final List<List<String>> scriptKeys = new ArrayList<>();
  private Object loadResult = 1L;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void stub() {
    when(catalogs.get(Fixtures.RIVERSIDE))
        .thenReturn(
            new ResourceCatalog(
                CatalogFixtures.riverside(),
                List.of(CatalogFixtures.OIL_CHANGE),
                List.of(new BayView(Fixtures.RIVERSIDE_BAY_1, "Bay 1")),
                List.of(new TechnicianView(Fixtures.SAM, "Sam", Set.of("OIL")))));
    when(redis.opsForValue()).thenReturn(values);
    when(redis.opsForHash()).thenReturn((HashOperations) hashes);
    when(redis.hasKey(anyString())).thenReturn(false);
    when(redis.execute(any(RedisCallback.class))).thenReturn(1_000L);
    when(hashes.entries(anyString())).thenReturn(Map.of());
    when(primary.intervals(any(), any())).thenReturn(List.of());
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RedisScript<?> script = inv.getArgument(0);
              List<String> keys = inv.getArgument(1);
              scriptKeys.add(keys);
              String source = script.getScriptAsString();
              if (source.contains("Atomically grants")) {
                return List.of("", "", "", "", "");
              }
              if (source.contains("One consistent read")) {
                List<Object> out = new ArrayList<>(List.of("1000", ""));
                keys.stream().filter(k -> k.startsWith("ledger:")).forEach(k -> out.add(List.of()));
                return out;
              }
              if (source.contains("Rebuilds the confirmed")) {
                return loadResult;
              }
              return 1L;
            });
  }

  @Test
  void everyScriptIsRoutedByKeysOfTheDealershipDay() {
    ledger.ensureLoaded(Fixtures.RIVERSIDE, MONDAY, NINE);
    ledger.acquire(
        Fixtures.RIVERSIDE,
        MONDAY,
        Fixtures.ALICE_SUB,
        List.of(new ResourcePair(Fixtures.RIVERSIDE_BAY_1, Fixtures.SAM)),
        NINE,
        NINE,
        "{}");
    Map<UUID, ReservationRow> rows =
        Map.of(
            UUID.randomUUID(), new ReservationRow(Fixtures.SAM, ReservationRow.TECHNICIAN, NINE));
    ledger.confirm(Fixtures.RIVERSIDE, MONDAY, UUID.randomUUID(), rows);
    ledger.release(Fixtures.RIVERSIDE, MONDAY, UUID.randomUUID(), Fixtures.ALICE_SUB);
    ledger.applyCommitted(Fixtures.RIVERSIDE, MONDAY, List.of(UUID.randomUUID()), rows);
    ledger.markDirty(Fixtures.RIVERSIDE, MONDAY);
    ledger.occupancy(Fixtures.RIVERSIDE, MONDAY, true, Fixtures.ALICE_SUB);
    ledger.occupancy(Fixtures.RIVERSIDE, MONDAY, true, null);
    ledger.heldBy(Fixtures.RIVERSIDE, MONDAY, Fixtures.SAM, NINE);

    assertThat(scriptKeys).hasSize(9);
    assertThat(scriptKeys)
        .allSatisfy(keys -> assertThat(keys).isNotEmpty().allMatch(k -> k.contains(KEYS.tag())));
    assertThat(scriptKeys.get(1))
        .contains(KEYS.ledger(Fixtures.RIVERSIDE_BAY_1), KEYS.ledger(Fixtures.SAM));
  }

  @Test
  void rebuildOvertakenEveryTimeIsUnavailableNotUsed() {
    loadResult = 0L;

    assertThatThrownBy(() -> ledger.ensureLoaded(Fixtures.RIVERSIDE, MONDAY, NINE))
        .isInstanceOfSatisfying(
            LedgerUnavailableException.class,
            e -> assertThat(LedgerFailures.isRedisFailure(e)).isTrue());
    assertThat(scriptKeys).hasSize(3);
  }

  @Test
  void loadedMarkerSkipsTheRebuild() {
    when(redis.hasKey(KEYS.loaded())).thenReturn(true);

    ledger.ensureLoaded(Fixtures.RIVERSIDE, MONDAY, NINE);

    assertThat(scriptKeys).isEmpty();
  }
}
