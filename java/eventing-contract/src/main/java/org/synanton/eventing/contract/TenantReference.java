package org.synanton.eventing.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Nested tenant identity retained by the frozen event envelope. */
public record TenantReference(@JsonProperty("id") String id) {

    public TenantReference {
        ContractValues.requireTenantId(id);
    }
}
