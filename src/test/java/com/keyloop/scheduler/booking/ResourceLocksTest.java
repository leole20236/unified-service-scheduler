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

  private static final UUID LOW = UUID.fromString("00000000-0000-0000-7000-000000000011");
  private static final UUID HIGH = UUID.fromString("00000000-0000-0000-b000-000000000011");
  // Signed comparison of the most significant bits would order this one first; PostgreSQL
  // compares unsigned bytes, so it sorts last.
  private static final UUID TOP = UUID.fromString("f0000000-0000-0000-0000-000000000001");

  @Test
  void ordersByPostgresUuidOrderAndDropsDuplicates() {
    assertThat(ResourceLocks.ordered(List.of(TOP, HIGH, LOW, HIGH)))
        .containsExactly(LOW, HIGH, TOP);
  }

  @Test
  void locksEveryDistinctResourceInOrder() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);

    new ResourceLocks(jdbc).lockAll(List.of(TOP, LOW, TOP, HIGH));

    InOrder order = inOrder(jdbc);
    for (UUID id : List.of(LOW, HIGH, TOP)) {
      order.verify(jdbc).queryForList("SELECT pg_advisory_xact_lock(?)", ResourceLocks.lockKey(id));
    }
    verify(jdbc, times(3)).queryForList(eq("SELECT pg_advisory_xact_lock(?)"), anyLong());
  }

  @Test
  void lockKeyFoldsBothHalves() {
    UUID id = new UUID(0x0F0FL, 0xF0F0L);
    assertThat(ResourceLocks.lockKey(id)).isEqualTo(0xFFFFL);
  }
}
