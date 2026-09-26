package com.trustagent.core.publicproduct.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PublicEvidencePolicyPropertiesTest {

    @Test
    void policyConfigurationRequiresVersionAndPositiveAge() {
        var valid = new PublicEvidencePolicyProperties(
                "public-evidence-confirmation-v1", Duration.ofHours(24));
        assertEquals(Duration.ofHours(24), valid.maxConfirmationAge());

        assertThrows(
                IllegalArgumentException.class,
                () -> new PublicEvidencePolicyProperties("", Duration.ofHours(24)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PublicEvidencePolicyProperties("public-evidence-confirmation-v1", Duration.ZERO));
    }
}
