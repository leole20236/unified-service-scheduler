package com.keyloop.scheduler.query;

import com.keyloop.scheduler.booking.AppointmentStatus;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.db.PrimaryRead;
import com.keyloop.scheduler.query.CursorCodec.Position;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A customer's own appointments (spec 9.4). Read from the primary: a customer who has just booked
 * must see the booking at once.
 */
@Service
@PrimaryRead
public class MyAppointmentsQuery {

  /** Page size when the client names none. */
  public static final int DEFAULT_LIMIT = 20;

  /** Largest page a client may ask for. */
  public static final int MAX_LIMIT = 100;

  private final AppointmentReadRepository reads;

  /** Creates the query. */
  public MyAppointmentsQuery(AppointmentReadRepository reads) {
    this.reads = reads;
  }

  /**
   * One page, newest start first. {@code from} is inclusive, {@code to} exclusive, both on the
   * start. A bad cursor or a limit outside 1..100 is a 400.
   */
  public Page<AppointmentView> list(
      UUID customerId,
      Optional<AppointmentStatus> status,
      Optional<Instant> from,
      Optional<Instant> to,
      Optional<String> cursor,
      int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new ValidationException(
          "1 field is invalid.",
          List.of(Map.of("field", "limit", "message", "must be between 1 and " + MAX_LIMIT)));
    }
    Optional<Position> after = cursor.map(CursorCodec::decode);
    List<AppointmentView> fetched = reads.page(customerId, status, from, to, after, limit + 1);
    if (fetched.size() <= limit) {
      return new Page<>(fetched, null);
    }
    List<AppointmentView> items = fetched.subList(0, limit);
    AppointmentView last = items.get(limit - 1);
    return new Page<>(items, CursorCodec.encode(new Position(last.start(), last.id())));
  }

  /** One of the customer's appointments; anyone else's is a 404. */
  public AppointmentView get(UUID customerId, UUID appointmentId) {
    return reads
        .findOwned(customerId, appointmentId)
        .orElseThrow(
            () -> new NotFoundException("Appointment " + appointmentId + " was not found."));
  }
}
