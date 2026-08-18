package org.synanton.topology.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResidencyPolicyValidatorTest {

    private final ResidencyPolicyValidator validator =
            new ResidencyPolicyValidator(Set.of("us-east-1", "eu-west-1"));

    @Test
    void shouldRejectUnknownRegion() {
        assertThatThrownBy(() -> validator.validate(List.of("ap-south-1"), false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRequireForceWhenDroppingRegionWithContent() {
        assertThatThrownBy(() -> validator.validate(List.of("us-east-1"), false, true))
                .isInstanceOf(ResidencyPolicyValidator.ResidencyDowngradeException.class);
    }
}
