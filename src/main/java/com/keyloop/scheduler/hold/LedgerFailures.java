package com.keyloop.scheduler.hold;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

/** Recognises the ways a Redis call fails: unreachable, too slow, or a script/command error. */
public final class LedgerFailures {

  private LedgerFailures() {}

  /**
   * Whether {@code e} means the ledger could not serve the call: connection failure, the 100 ms
   * command timeout (Lettuce reports it as {@link QueryTimeoutException}), a command/script error,
   * or a ledger that could not be rebuilt ({@link LedgerUnavailableException}).
   */
  public static boolean isRedisFailure(Throwable e) {
    return e instanceof LedgerUnavailableException
        || e instanceof RedisConnectionFailureException
        || e instanceof RedisSystemException
        || e instanceof QueryTimeoutException;
  }
}
