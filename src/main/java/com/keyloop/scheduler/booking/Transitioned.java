package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.common.time.TimeRange;

/** Result of a successful conditional status UPDATE: the new version and period. */
public record Transitioned(long version, TimeRange period) {}
