package com.keyloop.scheduler.catalog;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/** A dealership with its zone, bay buffer and weekly opening hours (closed days are absent). */
public record DealershipView(
    UUID id,
    String name,
    ZoneId zoneId,
    int bayBufferMinutes,
    Map<DayOfWeek, OpeningHours> openingHours) {}
