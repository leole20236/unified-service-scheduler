package com.keyloop.scheduler.booking;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Body of {@code POST …/{id}/resume}: when work restarts (default now, at most 15 minutes ago) and
 * optionally on which technician and bay; {@code overrideHold} takes time a customer is holding.
 */
public record ResumeActionRequest(
    OffsetDateTime resumeAt, UUID technicianId, UUID serviceBayId, boolean overrideHold) {}
