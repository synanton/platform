package org.synanton.eventing.contract;

import java.util.Objects;

final class ContractValues {

    private ContractValues() {
    }

    static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static String requireTenantId(String tenantId) {
        return requireText(tenantId, "tenantId");
    }

    static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
