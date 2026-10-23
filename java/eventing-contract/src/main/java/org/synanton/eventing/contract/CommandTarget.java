package org.synanton.eventing.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A stable reference to the aggregate or resource a command intends to change. */
@JsonPropertyOrder({"type", "id"})
public record CommandTarget(
        @JsonProperty("type") String type,
        @JsonProperty("id") String id) {

    public CommandTarget {
        ContractValues.requireText(type, "target.type");
        ContractValues.requireText(id, "target.id");
    }
}
