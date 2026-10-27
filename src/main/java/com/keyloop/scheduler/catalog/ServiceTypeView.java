package com.keyloop.scheduler.catalog;

import java.util.UUID;

/** A service offered by a dealership; the duration includes any dealership override. */
public record ServiceTypeView(
    UUID id, String code, String name, int durationMinutes, String requiredSkill) {}
