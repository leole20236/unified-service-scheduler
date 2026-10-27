package com.keyloop.scheduler.booking;

import com.keyloop.scheduler.catalog.BayView;
import com.keyloop.scheduler.catalog.ResourceCatalog;
import com.keyloop.scheduler.catalog.TechnicianView;
import com.keyloop.scheduler.common.api.BusinessRuleException;
import com.keyloop.scheduler.common.api.ProblemCodes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Service;

/** Ranks candidate (bay, technician) pairs for a job (spec 8.3). */
@Service
public class AllocationService {

  private final RandomGenerator random;

  /** Creates the service; tests pass a seeded generator. */
  public AllocationService(RandomGenerator random) {
    this.random = random;
  }

  /**
   * Candidate pairs, best first: qualified, free technicians (least booked minutes first, ties
   * random) times free bays (random order, buffer included). A pinned resource narrows its side to
   * that one resource; a pinned technician lacking the skills is a 422.
   */
  public List<ResourcePair> rank(
      ResourceCatalog catalog,
      JobPlan plan,
      Occupancy occupancy,
      Optional<UUID> pinnedBay,
      Optional<UUID> pinnedTech) {
    pinnedTech.ifPresent(id -> requireQualified(catalog, plan, id));
    List<TechnicianView> technicians = new ArrayList<>();
    for (TechnicianView t : catalog.technicians()) {
      if (t.skills().containsAll(plan.requiredSkills())
          && pinnedTech.map(t.id()::equals).orElse(true)
          && occupancy.isFree(t.id(), plan.period())) {
        technicians.add(t);
      }
    }
    Collections.shuffle(technicians, random);
    technicians.sort(Comparator.comparingLong(t -> occupancy.bookedMinutes(t.id())));
    List<BayView> bays = new ArrayList<>();
    for (BayView b : catalog.bays()) {
      if (pinnedBay.map(b.id()::equals).orElse(true)
          && occupancy.isFree(b.id(), plan.bayPeriod())) {
        bays.add(b);
      }
    }
    Collections.shuffle(bays, random);
    List<ResourcePair> pairs = new ArrayList<>();
    for (TechnicianView t : technicians) {
      for (BayView b : bays) {
        pairs.add(new ResourcePair(b.id(), t.id()));
      }
    }
    return pairs;
  }

  private static void requireQualified(ResourceCatalog catalog, JobPlan plan, UUID technicianId) {
    catalog.technicians().stream()
        .filter(t -> t.id().equals(technicianId))
        .filter(t -> !t.skills().containsAll(plan.requiredSkills()))
        .findFirst()
        .ifPresent(
            t -> {
              throw new BusinessRuleException(
                  ProblemCodes.TECHNICIAN_NOT_QUALIFIED,
                  t.displayName()
                      + " does not hold the skills this job needs: "
                      + String.join(", ", plan.requiredSkills())
                      + ".");
            });
  }
}
