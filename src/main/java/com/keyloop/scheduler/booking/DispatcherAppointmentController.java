package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.common.security.CallerContext;
import com.keyloop.scheduler.common.security.DealershipAccess;
import com.keyloop.scheduler.common.web.IfMatch;
import com.keyloop.scheduler.query.AppointmentView;
import com.keyloop.scheduler.query.DealershipAppointmentQuery;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A dispatcher reads one appointment, drags or resizes it ({@code PATCH}, merge-patch, If-Match
 * required) and moves it through its lifecycle with one action endpoint per transition (spec 14.4;
 * If-Match optional).
 */
@RestController
@RequestMapping("/api/v1/dealerships/{dealershipId}/appointments/{appointmentId}")
public class DispatcherAppointmentController {

  /** Media type of {@code PATCH} bodies (RFC 7396). */
  static final String MERGE_PATCH = "application/merge-patch+json";

  private static final String IF_MATCH = "If-Match";

  private final DealershipAppointmentQuery query;
  private final DispatcherChangeService changes;
  private final TransitionService transitions;

  /** Creates the controller. */
  public DispatcherAppointmentController(
      DealershipAppointmentQuery query,
      DispatcherChangeService changes,
      TransitionService transitions) {
    this.query = query;
    this.changes = changes;
    this.transitions = transitions;
  }

  @GetMapping
  ResponseEntity<AppointmentView> get(
      CallerContext caller, @PathVariable UUID dealershipId, @PathVariable UUID appointmentId) {
    DealershipAccess.requireStaff(caller, dealershipId);
    return ok(query.get(dealershipId, appointmentId));
  }

  @PatchMapping(consumes = MERGE_PATCH)
  ResponseEntity<AppointmentView> change(
      CallerContext caller,
      @PathVariable UUID dealershipId,
      @PathVariable UUID appointmentId,
      @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
      @RequestBody DispatcherChangeRequest body) {
    DealershipAccess.requireStaff(caller, dealershipId);
    long expected = IfMatch.require(ifMatch);
    return ok(changes.change(caller, dealershipId, appointmentId, expected, body));
  }

  @PostMapping("/check-in")
  ResponseEntity<AppointmentView> checkIn(
      CallerContext caller,
      @PathVariable UUID dealershipId,
      @PathVariable UUID appointmentId,
      @RequestHeader(value = IF_MATCH, required = false) String ifMatch) {
    return act(caller, dealershipId, appointmentId, Action.CHECK_IN, null, ifMatch);
  }

  @PostMapping("/start")
  ResponseEntity<AppointmentView> start(
      CallerContext caller,
      @PathVariable UUID dealershipId,
      @PathVariable UUID appointmentId,
      @RequestHeader(value = IF_MATCH, required = false) String ifMatch) {
    return act(caller, dealershipId, appointmentId, Action.START, null, ifMatch);
  }

  @PostMapping("/hold")
  ResponseEntity<AppointmentView> hold(
      CallerContext caller,
      @PathVariable UUID dealershipId,
      @PathVariable UUID appointmentId,
      @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody HoldActionRequest body) {
    return act(caller, dealershipId, appointmentId, Action.HOLD, body, ifMatch);
  }

  @PostMapping("/resume")
  ResponseEntity<AppointmentView> resume(
      CallerContext caller,
      @PathVariable UUID dealershipId,
      @PathVariable UUID appointmentId,
      @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
      @RequestBody(required = false) ResumeActionRequest body) {
    return act(caller, dealershipId, appointmentId, Action.RESUME, body, ifMatch);
  }

  @PostMapping("/complete")
  ResponseEntity<AppointmentView> complete(
      CallerContext caller,
      @PathVariable UUID dealershipId,
      @PathVariable UUID appointmentId,
      @RequestHeader(value = IF_MATCH, required = false) String ifMatch) {
    return act(caller, dealershipId, appointmentId, Action.COMPLETE, null, ifMatch);
  }

  @PostMapping("/no-show")
  ResponseEntity<AppointmentView> noShow(
      CallerContext caller,
      @PathVariable UUID dealershipId,
      @PathVariable UUID appointmentId,
      @RequestHeader(value = IF_MATCH, required = false) String ifMatch) {
    return act(caller, dealershipId, appointmentId, Action.NO_SHOW, null, ifMatch);
  }

  @PostMapping("/cancel")
  ResponseEntity<AppointmentView> cancel(
      CallerContext caller,
      @PathVariable UUID dealershipId,
      @PathVariable UUID appointmentId,
      @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody(required = false) CancelActionRequest body) {
    return act(caller, dealershipId, appointmentId, Action.CANCEL, body, ifMatch);
  }

  private ResponseEntity<AppointmentView> act(
      CallerContext caller,
      UUID dealershipId,
      UUID appointmentId,
      Action action,
      Object body,
      String ifMatch) {
    DealershipAccess.requireStaff(caller, dealershipId);
    return ok(
        transitions.apply(
            caller, dealershipId, appointmentId, action, body, IfMatch.optional(ifMatch)));
  }

  private static ResponseEntity<AppointmentView> ok(AppointmentView view) {
    return ResponseEntity.ok()
        .eTag("\"" + view.version() + "\"")
        .cacheControl(CacheControl.noStore())
        .body(view);
  }
}
