package org.synanton.security.idp;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Reads a flat file with lines: username:bcrypt_hash:uid:gid1,gid2,...
 * Verifies passwords using bcrypt (at.favre.lib:bcrypt).
 */
public class HtpasswdBackend {

    private static final Logger log = LoggerFactory.getLogger(HtpasswdBackend.class);

    private final Path filePath;
    private volatile Map<String, UserRecord> users = new ConcurrentHashMap<>();

    public HtpasswdBackend(String filePath) {
        this.filePath = Path.of(filePath);
        reload();
    }

    public void reload() {
        try {
            Map<String, UserRecord> loaded = Files.readAllLines(filePath)
                    .stream()
                    .filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(this::parseLine)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .collect(Collectors.toMap(UserRecord::username, u -> u));
            users = new ConcurrentHashMap<>(loaded);
            log.info("Loaded {} users from {}", users.size(), filePath);
        } catch (IOException e) {
            log.error("Failed to read htpasswd file {}: {}", filePath, e.getMessage());
        }
    }

    public Optional<UserRecord> authenticate(String username, String password) {
        UserRecord record = users.get(username);
        if (record == null) return Optional.empty();

        BCrypt.Result result = BCrypt.verifyer().verify(password.toCharArray(), record.hashedPassword());
        return result.verified ? Optional.of(record) : Optional.empty();
    }

    private Optional<UserRecord> parseLine(String line) {
        String[] parts = line.split(":", 4);
        if (parts.length < 4) {
            log.warn("Skipping malformed htpasswd line (expected 4 colon-separated fields)");
            return Optional.empty();
        }
        try {
            String username = parts[0].trim();
            String hash = parts[1].trim();
            int uid = Integer.parseInt(parts[2].trim());
            List<Integer> gids = Arrays.stream(parts[3].trim().split(","))
                    .map(s -> Integer.parseInt(s.trim()))
                    .toList();
            return Optional.of(new UserRecord(username, hash, uid, gids));
        } catch (NumberFormatException e) {
            log.warn("Skipping htpasswd line with invalid uid/gid: {}", line);
            return Optional.empty();
        }
    }
}
