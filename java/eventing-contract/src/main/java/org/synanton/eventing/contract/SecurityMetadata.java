package org.synanton.eventing.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Producer-supplied security claim; it is descriptive metadata, never an authorization grant. */
@JsonPropertyOrder({"actor_type", "actor_id", "classification", "policy_version", "data_handling_tags"})
public record SecurityMetadata(
        @JsonProperty("actor_type") ActorKind actorType,
        @JsonProperty("actor_id") String actorId,
        @JsonProperty("classification") String classification,
        @JsonProperty("policy_version") int policyVersion,
        @JsonProperty("data_handling_tags") List<String> dataHandlingTags) {

    public SecurityMetadata {
        Objects.requireNonNull(actorType, "actorType");
        ContractValues.requireText(actorId, "actorId");
        ContractValues.requireText(classification, "classification");
        ContractValues.requirePositive(policyVersion, "policyVersion");
        Objects.requireNonNull(dataHandlingTags, "dataHandlingTags");
        List<String> normalizedTags = new ArrayList<>(dataHandlingTags.size());
        for (String tag : dataHandlingTags) {
            normalizedTags.add(ContractValues.requireText(tag, "dataHandlingTags entry"));
        }
        normalizedTags.sort(String::compareTo);
        if (normalizedTags.stream().distinct().count() != normalizedTags.size()) {
            throw new IllegalArgumentException("dataHandlingTags must not contain duplicates");
        }
        dataHandlingTags = List.copyOf(normalizedTags);
    }
}
