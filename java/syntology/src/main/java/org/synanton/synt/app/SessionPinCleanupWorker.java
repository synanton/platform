package org.synanton.synt.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.synanton.synt.infra.jdbc.SessionPinRepository;

@Component
public class SessionPinCleanupWorker {

    private static final Logger log = LoggerFactory.getLogger(SessionPinCleanupWorker.class);

    private final SessionPinRepository sessionPinRepository;

    public SessionPinCleanupWorker(SessionPinRepository sessionPinRepository) {
        this.sessionPinRepository = sessionPinRepository;
    }

    @Scheduled(fixedDelay = 300_000)
    public void deleteExpiredPins() {
        int deleted = sessionPinRepository.deleteExpired();
        if (deleted > 0) {
            log.info("Deleted {} expired session pins", deleted);
        }
    }
}
