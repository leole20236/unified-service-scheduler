package com.keyloop.scheduler.common.db;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** PostgreSQL SQLSTATE detection anywhere in an exception's cause chain. */
public final class SqlStates {

  /** {@code exclusion_violation}: an EXCLUDE constraint (no_double_booking) rejected a row. */
  public static final String EXCLUSION_VIOLATION = "23P01";

  /** {@code unique_violation}. */
  public static final String UNIQUE_VIOLATION = "23505";

  /** {@code deadlock_detected}. */
  public static final String DEADLOCK_DETECTED = "40P01";

  private SqlStates() {}

  /**
   * Whether any {@link SQLException} in the cause chain (including chained next-exceptions) carries
   * the given SQLSTATE.
   */
  public static boolean has(Throwable failure, String sqlState) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
      if (t instanceof SQLException sql) {
        for (SQLException s = sql; s != null; s = s.getNextException()) {
          if (sqlState.equals(s.getSQLState())) {
            return true;
          }
        }
      }
    }
    return false;
  }
}
