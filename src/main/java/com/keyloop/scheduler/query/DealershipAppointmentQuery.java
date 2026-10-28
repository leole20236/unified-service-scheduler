package com.keyloop.scheduler.query;

import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.db.PrimaryRead;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * One appointment of a dealership for its dispatchers, read from the primary: the board fetches it
 * to refresh a card after a 412, so it must see the latest version.
 */
@Service
@PrimaryRead
public class DealershipAppointmentQuery {

  private final AppointmentReadRepository reads;

  /** Creates the query. */
  public DealershipAppointmentQuery(AppointmentReadRepository reads) {
    this.reads = reads;
  }

  /** The appointment if it belongs to the dealership, else 404. */
  public AppointmentView get(UUID dealershipId, UUID appointmentId) {
    return reads
        .find(appointmentId)
        .filter(v -> v.dealership().id().equals(dealershipId))
        .orElseThrow(
            () -> new NotFoundException("Appointment " + appointmentId + " was not found."));
  }
}
