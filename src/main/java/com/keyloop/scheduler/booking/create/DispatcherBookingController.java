package com.keyloop.scheduler.booking.create;

import com.keyloop.scheduler.booking.domain.BookingChannel;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.DealershipAccess;
import com.keyloop.scheduler.query.AppointmentView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A dispatcher books for a customer (phone / walk-in), optionally pinning bay or technician. */
@RestController
@RequestMapping("/api/v1/dealerships/{dealershipId}/appointments")
public class DispatcherBookingController {

  private final BookingService service;

  /** Creates the controller. */
  public DispatcherBookingController(BookingService service) {
    this.service = service;
  }

  @PostMapping
  ResponseEntity<AppointmentView> book(
      CallerContext caller,
      @PathVariable UUID dealershipId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody BookingRequest body) {
    DealershipAccess.requireStaff(caller, dealershipId);
    if (body.customerId() == null) {
      throw new ValidationException(
          "1 field is invalid.",
          List.of(Map.of("field", "customerId", "message", "must not be null")));
    }
    BookingRequest request =
        new BookingRequest(
            dealershipId,
            body.customerId(),
            body.vehicleId(),
            body.serviceTypeIds(),
            body.start(),
            body.customerNotes(),
            body.serviceBayId(),
            body.technicianId(),
            null,
            body.overrideHold());
    return AppointmentController.created(
        service.book(caller, BookingChannel.DISPATCHER, request, idempotencyKey),
        "/api/v1/dealerships/" + dealershipId + "/appointments");
  }
}
