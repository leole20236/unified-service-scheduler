package com.keyloop.scheduler.booking.domain;

/** Planned duration (sum of the booked services) and minutes already worked. */
public record AppointmentProgress(int plannedMinutes, int workedMinutes) {}
