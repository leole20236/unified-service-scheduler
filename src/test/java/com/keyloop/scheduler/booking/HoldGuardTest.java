package com.keyloop.scheduler.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.common.time.TimeRange;
import com.keyloop.scheduler.hold.SlotLedger;
import com.keyloop.scheduler.hold.SlotLedger.StoredHold;
import com.keyloop.scheduler.support.CatalogFixtures;
import com.keyloop.scheduler.support.Fixtures;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class HoldGuardTest {

  private static final DealershipView RIVERSIDE = CatalogFixtures.riverside();
  private static final Instant NINE = Instant.parse("2026-11-04T09:00:00Z");
  private static final TimeRange NINE_TO_TEN = new TimeRange(NINE, NINE.plusSeconds(3600));
  private static final LocalDate WEDNESDAY = LocalDate.of(2026, 11, 4);

  private final SlotLedger ledger = mock(SlotLedger.class);
  private final HoldGuard guard = new HoldGuard(ledger);

  private static StoredHold hold(UUID id, UUID user, Instant expires) {
    return new StoredHold(
        id,
        user,
        new ResourcePair(Fixtures.RIVERSIDE_BAY_1, Fixtures.SAM),
        NINE_TO_TEN,
        NINE_TO_TEN,
        "{}",
        expires);
  }

  @Test
  void datesCoverEveryLocalDayTouchedEndExclusiveAndCapped() {
    assertThat(HoldGuard.dates(RIVERSIDE, NINE_TO_TEN)).containsExactly(WEDNESDAY);
    Instant midnight = Instant.parse("2026-11-05T00:00:00Z");
    assertThat(HoldGuard.dates(RIVERSIDE, new TimeRange(NINE, midnight)))
        .containsExactly(WEDNESDAY);
    assertThat(HoldGuard.dates(RIVERSIDE, new TimeRange(NINE, midnight.plusSeconds(1))))
        .containsExactly(WEDNESDAY, WEDNESDAY.plusDays(1));
    assertThat(HoldGuard.dates(RIVERSIDE, new TimeRange(NINE, NINE.plus(Duration.ofDays(400)))))
        .hasSize(HoldGuard.MAX_DAYS);
  }

  @Test
  void oneHoldOnSeveralResourcesIsOneHeldEntryAndGoneHoldsAreIgnored() {
    UUID live = UUID.randomUUID();
    UUID gone = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    when(ledger.heldBy(eq(Fixtures.RIVERSIDE), eq(WEDNESDAY), anyMap()))
        .thenReturn(
            Map.of(Fixtures.SAM, List.of(live, gone), Fixtures.RIVERSIDE_BAY_1, List.of(live)));
    when(ledger.getHold(Fixtures.RIVERSIDE, WEDNESDAY, live))
        .thenReturn(Optional.of(hold(live, user, NINE)));
    when(ledger.getHold(Fixtures.RIVERSIDE, WEDNESDAY, gone)).thenReturn(Optional.empty());

    List<HoldGuard.Held> held =
        guard.find(
            RIVERSIDE, Map.of(Fixtures.SAM, NINE_TO_TEN, Fixtures.RIVERSIDE_BAY_1, NINE_TO_TEN));

    assertThat(held).hasSize(1);
    assertThat(held.get(0).resources()).isEqualTo(Set.of(Fixtures.SAM, Fixtures.RIVERSIDE_BAY_1));
    assertThat(held.get(0).userId()).isEqualTo(user);
  }

  @Test
  void refusalNamesTheEarliestExpiryInLocalTime() {
    List<HoldGuard.Held> held =
        List.of(
            new HoldGuard.Held(
                WEDNESDAY, UUID.randomUUID(), UUID.randomUUID(), NINE.plusSeconds(600), Set.of()),
            new HoldGuard.Held(WEDNESDAY, UUID.randomUUID(), UUID.randomUUID(), NINE, Set.of()));

    assertThat(guard.refusal(RIVERSIDE, held).getMessage())
        .isEqualTo(
            "A customer is holding this time until 09:00 (Europe/London). Resend with"
                + " overrideHold=true to take it.");
  }

  @Test
  void unreachableRedisMeansNoCheckAndNoRelease() {
    when(ledger.heldBy(any(), any(), anyMap()))
        .thenThrow(new RedisConnectionFailureException("down"));

    guard.clear(RIVERSIDE, Map.of(Fixtures.SAM, NINE_TO_TEN), false);

    verify(ledger, never()).release(any(), any(), any(), any());
  }

  @Test
  void otherFailuresPropagate() {
    when(ledger.heldBy(any(), any(), anyMap())).thenThrow(new IllegalStateException("bug"));

    assertThatThrownBy(() -> guard.find(RIVERSIDE, Map.of(Fixtures.SAM, NINE_TO_TEN)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void releaseFailuresOnRedisAreOnlyLogged() {
    UUID holdId = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    HoldGuard.Held held = new HoldGuard.Held(WEDNESDAY, holdId, user, NINE, Set.of());
    doThrow(new RedisConnectionFailureException("down"))
        .when(ledger)
        .release(Fixtures.RIVERSIDE, WEDNESDAY, holdId, user);

    guard.release(RIVERSIDE, List.of(held));

    doThrow(new IllegalStateException("bug"))
        .when(ledger)
        .release(Fixtures.RIVERSIDE, WEDNESDAY, holdId, user);
    assertThatThrownBy(() -> guard.release(RIVERSIDE, List.of(held)))
        .isInstanceOf(IllegalStateException.class);
  }
}
