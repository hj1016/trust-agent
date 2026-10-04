package com.trustagent.core.internalpolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class BusinessTimePolicyTest {

    @Test
    void utc2330BelongsToNextBusinessDayRegardlessOfJvmTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String defaultZone : new String[] {"UTC", "America/New_York", "Pacific/Honolulu"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(defaultZone));
                BusinessTimePolicy policy = new BusinessTimePolicy("Asia/Seoul");
                assertEquals(LocalDate.of(2026, 9, 11), policy.businessDate(Instant.parse("2026-09-10T23:30:00Z")));
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
