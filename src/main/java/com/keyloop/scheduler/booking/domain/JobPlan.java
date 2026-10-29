package com.keyloop.scheduler.booking.domain;

import com.keyloop.scheduler.catalog.ServiceTypeView;
import com.keyloop.scheduler.common.time.TimeRange;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * The computed shape of a job: services, total duration, skills, the technician period, the bay
 * period (period plus buffer) and the dealership-local start.
 */
public record JobPlan(
    List<ServiceTypeView> services,
    int durationMinutes,
    Set<String> requiredSkills,
    TimeRange period,
    TimeRange bayPeriod,
    LocalDate localDate,
    LocalDateTime localStart) {}
