package com.keyloop.scheduler.query;

/** One service of an appointment with its position in the booking and duration snapshot. */
public record ServiceLine(int position, String code, String name, int durationMinutes) {}
