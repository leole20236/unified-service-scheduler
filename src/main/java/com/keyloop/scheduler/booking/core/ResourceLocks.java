package com.keyloop.scheduler.booking.core;

import java.util.Collection;
import java.util.Comparator;
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
 *
 * <p>One {@code lockAll} per transaction, before any UPDATE of an existing appointment row: a
 * writer that updates the appointment first and then waits for a lock can deadlock with one that
 * holds the lock and waits for the appointment row, and a second {@code lockAll} later in the same
 * transaction would take keys out of the global ascending order while earlier ones are still held.
 * Lock old and new resources together; if more resources turn out to be needed (the appointment
 * moved while the transaction queued), roll back and restart the whole attempt (see {@code
 * CustomerCancelService}).
 */
@Component
public class ResourceLocks {

  /**
   * PostgreSQL's uuid order (unsigned bytes = order of the lowercase hex text): the order rows are
   * inserted in.
   */
  static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);

  private final JdbcTemplate jdbc;

  /** Creates the helper over the routing data source (the caller's write transaction). */
  public ResourceLocks(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Takes {@code pg_advisory_xact_lock} once per distinct lock key of the resources, in ascending
   * key order. Ordering by the key actually locked (not by resource id) keeps one global order even
   * when two resources share a key, so two writers never wait on each other in a cycle. Must run
   * inside the caller's read-write transaction: outside one the locks would be released at once.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockAll(Collection<UUID> resourceIds) {
    for (long key : keys(resourceIds)) {
      jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", key);
    }
  }

  /** The distinct lock keys of the resources, ascending: the order locks are taken in. */
  static long[] keys(Collection<UUID> resourceIds) {
    return resourceIds.stream().mapToLong(ResourceLocks::lockKey).distinct().sorted().toArray();
  }

  /**
   * 64-bit advisory lock key of a resource. Two resources sharing a key merely serialise each
   * other.
   */
  static long lockKey(UUID resourceId) {
    return resourceId.getMostSignificantBits() ^ resourceId.getLeastSignificantBits();
  }
}
