package org.synanton.eventing.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.util.Objects;

/** Canonical JSON mapper for eventing contract records and their typed payloads. */
public final class EventingJson {

    private EventingJson() {
    }

    /** Creates a mapper with ISO-8601 timestamps, UUID strings, Optional support, and additive input. */
    public static ObjectMapper newMapper() {
        return JsonMapper.builder()
                .addModule(new Jdk8Module())
                .addModule(new JavaTimeModule())
                .serializationInclusion(JsonInclude.Include.NON_ABSENT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    /** Deserializes an event and rejects any event type/schema version not declared by the registry. */
    public static <T> Event<T> readEvent(
            byte[] json, TypeReference<Event<T>> eventType, EventTypeRegistry registry)
            throws IOException {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(registry, "registry");
        Event<T> event = newMapper().readValue(json, eventType);
        registry.validate(event);
        return event;
    }

    /** Deserializes a command and rejects any command type/schema version not declared by the registry. */
    public static <T> Command<T> readCommand(
            byte[] json, TypeReference<Command<T>> commandType, CommandTypeRegistry registry)
            throws IOException {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(commandType, "commandType");
        Objects.requireNonNull(registry, "registry");
        Command<T> command = newMapper().readValue(json, commandType);
        registry.validate(command);
        return command;
    }
}
