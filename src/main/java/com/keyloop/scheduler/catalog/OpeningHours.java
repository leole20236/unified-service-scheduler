package com.keyloop.scheduler.catalog;

import java.time.LocalTime;

/** Local wall-clock opening and closing time of a dealership on one weekday. */
public record OpeningHours(LocalTime opensAt, LocalTime closesAt) {}
