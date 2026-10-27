package com.keyloop.scheduler.catalog;

import java.util.List;

/** Everything the scheduler needs to know about one dealership; active resources only. */
public record ResourceCatalog(
    DealershipView dealership,
    List<ServiceTypeView> services,
    List<BayView> bays,
    List<TechnicianView> technicians) {}
