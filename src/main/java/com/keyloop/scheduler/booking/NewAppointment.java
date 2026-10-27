package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.ServiceTypeView;
import com.keyloop.scheduler.common.time.TimeRange;
import java.util.List;
import java.util.UUID;

/** A new appointment row and its services (in booking order). */
public record NewAppointment(
    UUID id,
    UUID dealershipId,
    UUID customerId,
    UUID vehicleId,
    UUID serviceBayId,
    UUID technicianId,
    TimeRange period,
    String customerNotes,
    UUID createdBy,
    List<ServiceTypeView> services) {}
