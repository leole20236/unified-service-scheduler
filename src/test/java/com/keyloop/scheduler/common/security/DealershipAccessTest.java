package com.keyloop.scheduler.common.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keyloop.scheduler.common.api.ForbiddenException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DealershipAccessTest {

  private final UUID dealership = UUID.randomUUID();

  @Test
  void staffWithDealershipPasses() {
    CallerContext staff =
        new CallerContext(UUID.randomUUID(), Set.of(Role.DISPATCHER), Set.of(dealership));
    assertThatCode(() -> DealershipAccess.requireStaff(staff, dealership))
        .doesNotThrowAnyException();
  }

  @Test
  void customerIsForbidden() {
    CallerContext customer = new CallerContext(UUID.randomUUID(), Set.of(Role.CUSTOMER), Set.of());
    assertThatThrownBy(() -> DealershipAccess.requireStaff(customer, dealership))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void dispatcherWithoutDealershipIsForbidden() {
    CallerContext staff =
        new CallerContext(UUID.randomUUID(), Set.of(Role.DISPATCHER), Set.of(UUID.randomUUID()));
    assertThatThrownBy(() -> DealershipAccess.requireStaff(staff, dealership))
        .isInstanceOf(ForbiddenException.class)
        .hasMessageContaining(dealership.toString());
  }

  @Test
  void requireRoleChecksRole() {
    CallerContext customer = new CallerContext(UUID.randomUUID(), Set.of(Role.CUSTOMER), Set.of());
    assertThatCode(() -> DealershipAccess.requireRole(customer, Role.CUSTOMER))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> DealershipAccess.requireRole(customer, Role.DISPATCHER))
        .isInstanceOf(ForbiddenException.class);
  }
}
