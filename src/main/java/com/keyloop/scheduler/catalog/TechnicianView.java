package com.keyloop.scheduler.catalog;

import java.util.Set;
import java.util.UUID;

/** An active technician and the skills they hold. */
public record TechnicianView(UUID id, String displayName, Set<String> skills) {}
