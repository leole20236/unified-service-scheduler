package com.keyloop.scheduler.common.security;

import java.util.Set;
import java.util.UUID;

/** The authenticated caller, as asserted by the gateway headers. */
public record CallerContext(UUID userId, Set<Role> roles, Set<UUID> dealershipIds) {

  /** Request attribute under which {@link IdentityHeaderFilter} stores the caller. */
  public static final String REQUEST_ATTRIBUTE = CallerContext.class.getName();

  /** Returns whether the caller holds the given role. */
  public boolean has(Role role) {
    return roles.contains(role);
  }
}
