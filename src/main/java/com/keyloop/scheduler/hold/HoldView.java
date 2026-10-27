package com.keyloop.scheduler.hold;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A granted hold: its id, when it lapses, and the technician period it covers (UTC). */
public record HoldView(
    UUID holdId, OffsetDateTime expiresAt, OffsetDateTime start, OffsetDateTime end) {}
