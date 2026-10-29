package com.keyloop.scheduler.query;

import com.keyloop.scheduler.booking.change.CustomerCancelService;
import com.keyloop.scheduler.booking.change.RescheduleService;
import com.keyloop.scheduler.booking.domain.AppointmentStatus;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.DealershipAccess;
import com.keyloop.scheduler.common.security.Role;
import com.keyloop.scheduler.common.web.IfMatch;
import com.keyloop.scheduler.customer.CustomerService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A customer's own appointments: list, detail, reschedule via a hold, cancel (spec 14.3). */
@RestController
@RequestMapping("/api/v1/me/appointments")
public class MeAppointmentController {

  private final MyAppointmentsQuery query;
  private final RescheduleService rescheduling;
  private final CustomerCancelService cancelling;
  private final CustomerService customers;

  /** Creates the controller. */
  public MeAppointmentController(
      MyAppointmentsQuery query,
      RescheduleService rescheduling,
      CustomerCancelService cancelling,
      CustomerService customers) {
    this.query = query;
    this.rescheduling = rescheduling;
    this.cancelling = cancelling;
    this.customers = customers;
  }

  @GetMapping
  ResponseEntity<Page<AppointmentView>> list(
      CallerContext caller,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "" + MyAppointmentsQuery.DEFAULT_LIMIT) int limit) {
    DealershipAccess.requireRole(caller, Role.CUSTOMER);
    UUID customerId = customers.currentCustomer(caller);
    Page<AppointmentView> page =
        query.list(
            customerId,
            Optional.ofNullable(status).map(MeAppointmentController::parseStatus),
            Optional.ofNullable(from).map(v -> parseInstant("from", v)),
            Optional.ofNullable(to).map(v -> parseInstant("to", v)),
            Optional.ofNullable(cursor),
            limit);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
  }

  @GetMapping("/{appointmentId}")
  ResponseEntity<AppointmentView> get(CallerContext caller, @PathVariable UUID appointmentId) {
    DealershipAccess.requireRole(caller, Role.CUSTOMER);
    return ok(query.get(customers.currentCustomer(caller), appointmentId));
  }

  @PostMapping("/{appointmentId}/reschedule")
  ResponseEntity<AppointmentView> reschedule(
      CallerContext caller,
      @PathVariable UUID appointmentId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @Valid @RequestBody RescheduleRequest body) {
    DealershipAccess.requireRole(caller, Role.CUSTOMER);
    long expected = IfMatch.require(ifMatch);
    return ok(rescheduling.customerReschedule(caller, appointmentId, expected, body.holdId()));
  }

  @PostMapping("/{appointmentId}/cancel")
  ResponseEntity<AppointmentView> cancel(
      CallerContext caller,
      @PathVariable UUID appointmentId,
      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
    DealershipAccess.requireRole(caller, Role.CUSTOMER);
    return ok(cancelling.cancel(caller, appointmentId, IfMatch.optional(ifMatch)));
  }

  private static ResponseEntity<AppointmentView> ok(AppointmentView view) {
    return ResponseEntity.ok()
        .eTag("\"" + view.version() + "\"")
        .cacheControl(CacheControl.noStore())
        .body(view);
  }

  private static AppointmentStatus parseStatus(String value) {
    try {
      return AppointmentStatus.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw invalid("status", "must be one of " + List.of(AppointmentStatus.values()));
    }
  }

  private static Instant parseInstant(String field, String value) {
    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (DateTimeParseException e) {
      throw invalid(field, "must be an ISO-8601 date-time with an offset");
    }
  }

  private static ValidationException invalid(String field, String message) {
    return new ValidationException(
        "1 field is invalid.", List.of(Map.of("field", field, "message", message)));
  }
}
