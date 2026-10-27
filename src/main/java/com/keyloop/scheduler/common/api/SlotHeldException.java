package com.keyloop.scheduler.common.api;

/** Problem SLOT_HELD. */
public class SlotHeldException extends ApiException {

  /** Creates the problem with its human-readable detail. */
  public SlotHeldException(String detail) {
    super(ProblemCodes.SLOT_HELD, detail);
  }
}
