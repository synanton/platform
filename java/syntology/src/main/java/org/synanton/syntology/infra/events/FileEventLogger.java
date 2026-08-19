package org.synanton.syntology.infra.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.synanton.syntology.app.SyntologyProperties;
import org.synanton.syntology.domain.port.out.EventPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

@Component
public class FileEventLogger implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(FileEventLogger.class);

    private final Path logPath;

    public FileEventLogger(SyntologyProperties properties) {
        this.logPath = Path.of(properties.events().logPath());
        try {
            Files.createDirectories(logPath.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create events log directory", e);
        }
    }

    @Override
    public void publish(String eventType, String payload) {
        String line = Instant.now() + " " + eventType + " " + payload + System.lineSeparator();
        try {
            Files.writeString(logPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to write event log: {}", e.getMessage());
        }
        log.info("syntology event {} {}", eventType, payload);
    }
}
