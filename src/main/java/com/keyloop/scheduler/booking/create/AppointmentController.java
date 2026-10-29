package com.keyloop.scheduler.booking.create;

import com.keyloop.scheduler.booking.domain.BookingChannel;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.DealershipAccess;
import com.keyloop.scheduler.common.security.Role;
import com.keyloop.scheduler.query.AppointmentView;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A customer books for themselves; the system allocates bay and technician. */
@RestController
@RequestMapping(AppointmentController.ROOT)
public class AppointmentController {

  static final String ROOT = "/api/v1/me/appointments";

  private final BookingService service;

  /** Creates the controller. */
  public AppointmentController(BookingService service) {
    this.service = service;
  }

  @PostMapping
  ResponseEntity<AppointmentView> book(
      CallerContext caller,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody BookingRequest body) {
    DealershipAccess.requireRole(caller, Role.CUSTOMER);
    if (body.dealershipId() == null) {
      throw new ValidationException(
          "1 field is invalid.",
          List.of(Map.of("field", "dealershipId", "message", "must not be null")));
    }
    BookingRequest request =
        new BookingRequest(
            body.dealershipId(),
            null,
            body.vehicleId(),
            body.serviceTypeIds(),
            body.start(),
            body.customerNotes(),
            null,
            null,
            body.holdId(),
            false);
    return created(service.book(caller, BookingChannel.CUSTOMER, request, idempotencyKey), ROOT);
  }

  /** {@code 201} with Location, ETag and no-store; replays add {@code Idempotency-Replayed}. */
  static ResponseEntity<AppointmentView> created(BookingResult result, String collection) {
    AppointmentView view = result.body();
    ResponseEntity.BodyBuilder builder =
        ResponseEntity.status(result.httpStatus())
            .location(URI.create(collection + "/" + view.id()))
            .eTag("\"" + view.version() + "\"")
            .cacheControl(CacheControl.noStore());
    if (result.replayed()) {
      builder.header("Idempotency-Replayed", "true");
    }
    return builder.body(view);
  }
}
