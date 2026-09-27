package com.trustagent.core.internalpolicy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

public final class BusinessTimePolicy {

    public static final String POLICY_VERSION = "internal-business-time-v1";

    private final ZoneId businessZone;

    public BusinessTimePolicy(String businessTimezone) {
        this.businessZone = ZoneId.of(Objects.requireNonNull(businessTimezone));
    }

    public LocalDate businessDate(Instant instant) {
        return Objects.requireNonNull(instant).atZone(businessZone).toLocalDate();
    }

    public String businessTimezone() {
        return businessZone.getId();
    }
}
