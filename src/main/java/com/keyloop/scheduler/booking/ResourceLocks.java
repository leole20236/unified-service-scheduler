package com.keyloop.scheduler.booking;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-resource writer queue in front of the EXCLUDE constraint (spec 8.1). Every writer of {@code
 * resource_reservation} (booking, confirm, reschedule, drag-and-drop, resume, blocks) calls {@link
 * #lockAll} inside its own transaction before inserting or updating reservations.
 *
 * <p>Why: PostgreSQL adds a row's index entry <em>before</em> checking the EXCLUDE constraint, so
 * two transactions inserting overlapping reservations for one resource at the same moment each find
 * the other's uncommitted entry and wait for the other transaction to end: a deadlock (40P01) that
 * the detector breaks only after {@code deadlock_timeout}, whatever the insert order. Queueing
 * writers of the same resource on a transaction-scoped advisory lock first means the exclusion
 * check only ever sees committed rows, so a race ends in a clean 23P01 or success. The lock only
 * orders writers; EXCLUDE remains the guarantee. Locks are released at commit or rollback.
 */
@Component
public class ResourceLocks {

  /** PostgreSQL's uuid order: unsigned bytes, which equals the order of the lowercase hex text. */
  static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);

  private final JdbcTemplate jdbc;

  /** Creates the helper over the routing data source (the caller's write transaction). */
  public ResourceLocks(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Takes {@code pg_advisory_xact_lock} on every distinct resource, in ascending resource-id order
   * (one global order, so two writers never wait on each other in a cycle). Must run inside the
   * caller's read-write transaction: outside one the locks would be released immediately.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockAll(Collection<UUID> resourceIds) {
    for (UUID id : ordered(resourceIds)) {
      jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", lockKey(id));
    }
  }

  /** The distinct resource ids in lock order. */
  static List<UUID> ordered(Collection<UUID> resourceIds) {
    return resourceIds.stream().distinct().sorted(UUID_ORDER).toList();
  }

  /**
   * 64-bit advisory lock key of a resource. Two resources sharing a key merely serialise each
   * other.
   */
  static long lockKey(UUID resourceId) {
    return resourceId.getMostSignificantBits() ^ resourceId.getLeastSignificantBits();
  }
}
