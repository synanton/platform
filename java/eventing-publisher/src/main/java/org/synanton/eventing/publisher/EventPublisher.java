package org.synanton.eventing.publisher;

import org.synanton.eventing.contract.Event;

@FunctionalInterface
public interface EventPublisher {

    void publish(String verifiedTenantId, Event<?> event);
}
