package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.common.time.TimeRange;
import java.util.UUID;

/** One active reservation as the slot ledger mirrors it. */
public record ReservedInterval(UUID reservationId, UUID resourceId, TimeRange period) {}
