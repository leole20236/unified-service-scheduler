package com.keyloop.scheduler.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

class ResourceLocksTest {

  private static final String LOCK = "SELECT pg_advisory_xact_lock(?)";
  private static final UUID LOW = UUID.fromString("00000000-0000-0000-7000-000000000011");
  private static final UUID HIGH = UUID.fromString("00000000-0000-0000-b000-000000000011");
  private static final UUID TOP = UUID.fromString("f0000000-0000-0000-0000-000000000001");
  // Same MSB^LSB, so the same advisory lock key, yet different resources.
  private static final UUID TWIN_A = new UUID(0x1234L, 0x00F0L);
  private static final UUID TWIN_B = new UUID(0x00F0L, 0x1234L);

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

  @Test
  void locksDistinctKeysInAscendingKeyOrder() {
    new ResourceLocks(jdbc).lockAll(List.of(TOP, LOW, TOP, HIGH));

    long[] keys = ResourceLocks.keys(List.of(TOP, LOW, HIGH));
    assertThat(keys).isSorted().hasSize(3);
    InOrder order = inOrder(jdbc);
    for (long key : keys) {
      order.verify(jdbc).queryForList(LOCK, key);
    }
    verify(jdbc, times(3)).queryForList(eq(LOCK), anyLong());
  }

  @Test
  void keyOrderIsTheSameWhicheverOrderResourcesArrive() {
    assertThat(ResourceLocks.keys(List.of(HIGH, TWIN_A, LOW)))
        .containsExactly(ResourceLocks.keys(List.of(LOW, TWIN_B, HIGH, TWIN_A)));
  }

  @Test
  void resourcesSharingOneKeyAreLockedOnce() {
    assertThat(ResourceLocks.lockKey(TWIN_A)).isEqualTo(ResourceLocks.lockKey(TWIN_B));

    new ResourceLocks(jdbc).lockAll(List.of(TWIN_B, TWIN_A));

    verify(jdbc, times(1)).queryForList(LOCK, ResourceLocks.lockKey(TWIN_A));
    verify(jdbc, times(1)).queryForList(eq(LOCK), anyLong());
  }

  @Test
  void lockKeyFoldsBothHalves() {
    assertThat(ResourceLocks.lockKey(new UUID(0x0F0FL, 0xF0F0L))).isEqualTo(0xFFFFL);
  }

  @Test
  void insertOrderIsPostgresUuidOrder() {
    assertThat(List.of(TOP, HIGH, LOW).stream().sorted(ResourceLocks.UUID_ORDER))
        .containsExactly(LOW, HIGH, TOP);
  }
}
