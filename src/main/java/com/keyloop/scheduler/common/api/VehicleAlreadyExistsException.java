package com.keyloop.scheduler.common.api;

/** Problem VEHICLE_ALREADY_EXISTS. */
public class VehicleAlreadyExistsException extends ApiException {

  /** Creates the problem with its human-readable detail. */
  public VehicleAlreadyExistsException(String detail) {
    super(ProblemCodes.VEHICLE_ALREADY_EXISTS, detail);
  }
}
