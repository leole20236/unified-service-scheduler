package com.keyloop.scheduler.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of the walk-in customer endpoint; at least one of email/phone is required. */
public record NewCustomerRequest(
    @NotBlank @Size(max = 120) String displayName,
    @Email @Size(max = 200) String email,
    @Size(max = 30) String phone) {}
