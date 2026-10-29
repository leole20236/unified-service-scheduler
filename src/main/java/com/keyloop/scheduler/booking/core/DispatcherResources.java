package com.keyloop.scheduler.booking.core;

import com.keyloop.scheduler.booking.domain.SlotMessages;
import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.DealershipView;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.ServiceTypeView;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.api.BusinessRuleException;
import com.keyloop.scheduler.common.api.NotFoundException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.api.ValidationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lookups and checks shared by the dispatcher's writes (spec 6.4). */
public final class DispatcherResources {

  private DispatcherResources() {}

  /** The dealership's active bay, else 404. */
  public static BayView bay(ResourceCatalog catalog, UUID id) {
    return catalog.bays().stream()
        .filter(b -> b.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Service bay not found."));
  }

  /** The dealership's active technician, else 404. */
  public static TechnicianView technician(ResourceCatalog catalog, UUID id) {
    return catalog.technicians().stream()
        .filter(t -> t.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Technician not found."));
  }

  /** The booked services the dealership still offers, in booking order. */
  public static List<ServiceTypeView> services(ResourceCatalog catalog, List<UUID> serviceTypeIds) {
    List<ServiceTypeView> services = new ArrayList<>();
    for (UUID id : serviceTypeIds) {
      catalog.services().stream().filter(s -> s.id().equals(id)).forEach(services::add);
    }
    return services;
  }

  /** 422 TECHNICIAN_NOT_QUALIFIED unless the technician holds every required skill. */
  public static void requireQualified(TechnicianView technician, List<ServiceTypeView> services) {
    for (ServiceTypeView s : services) {
      if (!technician.skills().contains(s.requiredSkill())) {
        throw new BusinessRuleException(
            ProblemCodes.TECHNICIAN_NOT_QUALIFIED,
            technician.displayName()
                + " does not have the "
                + s.requiredSkill()
                + " skill required by "
                + s.name()
                + ".");
      }
    }
  }

  /** The SLOT_UNAVAILABLE detail of a refused dispatcher move or resume. */
  public static String taken(DealershipView d, Instant start) {
    return "The chosen bay or technician is not free at "
        + SlotMessages.at(start.atZone(d.zoneId()).toLocalDateTime())
        + " ("
        + d.zoneId().getId()
        + ").";
  }

  /** A 400 naming one field. */
  public static ValidationException invalid(String field, String message) {
    return new ValidationException(
        "1 field is invalid.", List.of(Map.of("field", field, "message", message)));
  }

  /** The other appointments among the conflicts. */
  public static List<UUID> others(List<UUID> conflicts, UUID own) {
    return conflicts.stream().filter(id -> !id.equals(own)).toList();
  }
}
