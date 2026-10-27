package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.ServiceTypeView;
import com.keyloop.scheduler.common.api.BusinessRuleException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import com.keyloop.scheduler.common.api.ValidationException;
import com.keyloop.scheduler.common.time.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Computes a {@link JobPlan} from requested service types and a start. */
public final class JobPlanner {

  private static final int MAX_SERVICES = 5;

  private JobPlanner() {}

  /** Plans the job; throws {@link ValidationException} or a SERVICE_NOT_OFFERED problem. */
  public static JobPlan plan(
      ResourceCatalog catalog, List<UUID> serviceTypeIds, OffsetDateTime start) {
    validateIds(serviceTypeIds);
    Map<UUID, ServiceTypeView> offered =
        catalog.services().stream()
            .collect(Collectors.toMap(ServiceTypeView::id, Function.identity()));
    List<ServiceTypeView> services =
        serviceTypeIds.stream()
            .map(
                id -> {
                  ServiceTypeView service = offered.get(id);
                  if (service == null) {
                    throw new BusinessRuleException(
                        ProblemCodes.SERVICE_NOT_OFFERED,
                        catalog.dealership().name()
                            + " does not offer one of the requested services.");
                  }
                  return service;
                })
            .toList();
    int minutes = services.stream().mapToInt(ServiceTypeView::durationMinutes).sum();
    Set<String> skills =
        services.stream()
            .map(ServiceTypeView::requiredSkill)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Instant startInstant = start.toInstant();
    TimeRange period = new TimeRange(startInstant, startInstant.plus(Duration.ofMinutes(minutes)));
    TimeRange bayPeriod =
        period.extendEnd(Duration.ofMinutes(catalog.dealership().bayBufferMinutes()));
    LocalDateTime localStart = startInstant.atZone(catalog.dealership().zoneId()).toLocalDateTime();
    return new JobPlan(
        services, minutes, skills, period, bayPeriod, localStart.toLocalDate(), localStart);
  }

  private static void validateIds(List<UUID> ids) {
    if (ids.isEmpty() || ids.size() > MAX_SERVICES) {
      throw invalid("Between 1 and 5 services are required.", "A booking needs 1 to 5 services");
    }
    if (new HashSet<>(ids).size() != ids.size()) {
      throw invalid("1 field is invalid.", "services must be distinct");
    }
  }

  private static ValidationException invalid(String detail, String message) {
    return new ValidationException(
        detail, List.of(Map.of("field", "serviceTypeIds", "message", message)));
  }
}
