package com.keyloop.scheduler.booking;

/** A dispatcher action on an appointment's lifecycle (spec 6.2), one endpoint each (spec 14.4). */
public enum Action {
  CHECK_IN("check-in", "check in", false),
  START("start", "start", false),
  HOLD("hold", "put on hold", true),
  RESUME("resume", "resume", true),
  COMPLETE("complete", "complete", true),
  NO_SHOW("no-show", "mark as no-show", true),
  CANCEL("cancel", "cancel", true);

  private final String pathSegment;
  private final String verb;
  private final boolean touchesReservations;

  Action(String pathSegment, String verb, boolean touchesReservations) {
    this.pathSegment = pathSegment;
    this.verb = verb;
    this.touchesReservations = touchesReservations;
  }

  /** The action's path segment, e.g. {@code no-show} in {@code POST …/{id}/no-show}. */
  public String pathSegment() {
    return pathSegment;
  }

  /** The verb used in messages, e.g. "check in". */
  public String verb() {
    return verb;
  }

  /** Whether the action changes or releases the appointment's reservations. */
  public boolean touchesReservations() {
    return touchesReservations;
  }
}
