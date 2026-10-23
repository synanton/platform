package org.synanton.eventing.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Identifies the service and service release that emitted an event. */
@JsonPropertyOrder({"service", "version"})
public record Producer(
        @JsonProperty("service") String service,
        @JsonProperty("version") String version) {

    public Producer {
        ContractValues.requireText(service, "producer.service");
        ContractValues.requireText(version, "producer.version");
    }
}
