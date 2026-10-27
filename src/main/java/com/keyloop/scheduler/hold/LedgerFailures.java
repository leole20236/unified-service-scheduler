package com.keyloop.scheduler.hold;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

/** Recognises the ways a Redis call fails: unreachable, too slow, or a script/command error. */
public final class LedgerFailures {

  private LedgerFailures() {}

  /**
   * Whether {@code e} means Redis could not serve the call: connection failure, the 100 ms command
   * timeout (Lettuce reports it as {@link QueryTimeoutException}), or a command/script error.
   */
  public static boolean isRedisFailure(Throwable e) {
    return e instanceof RedisConnectionFailureException
        || e instanceof RedisSystemException
        || e instanceof QueryTimeoutException;
  }
}
