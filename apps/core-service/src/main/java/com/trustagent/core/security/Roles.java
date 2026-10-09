package com.trustagent.core.security;

import java.util.List;

/** 역할 두 개(ADR-014 4항). ADMIN은 없다. */
public final class Roles {

    public static final String STAFF = "STAFF";
    public static final String REVIEWER = "REVIEWER";
    public static final List<String> ALL = List.of(STAFF, REVIEWER);
    static final String AUTHORITY_PREFIX = "ROLE_";

    private Roles() {
    }

    public static boolean isRole(String value) {
        return ALL.contains(value);
    }
}
