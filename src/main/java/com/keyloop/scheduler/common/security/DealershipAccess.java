package com.keyloop.scheduler.common.security;

import com.keyloop.scheduler.common.api.ForbiddenException;
import java.util.UUID;

/** Authorisation guards for dealership-scoped (staff) endpoints. */
public final class DealershipAccess {

  private DealershipAccess() {}

  /** Requires a dispatcher who may act for the given dealership. */
  public static void requireStaff(CallerContext caller, UUID dealershipId) {
    requireRole(caller, Role.DISPATCHER);
    if (!caller.dealershipIds().contains(dealershipId)) {
      throw new ForbiddenException("Your account cannot act for dealership " + dealershipId + ".");
    }
  }

  /** Requires the caller to hold the given role. */
  public static void requireRole(CallerContext caller, Role role) {
    if (!caller.has(role)) {
      throw new ForbiddenException("This action requires the " + role + " role.");
    }
  }
}
