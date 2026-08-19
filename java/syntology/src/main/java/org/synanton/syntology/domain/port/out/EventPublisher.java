package org.synanton.syntology.domain.port.out;

public interface EventPublisher {

    void publish(String eventType, String payload);
}
