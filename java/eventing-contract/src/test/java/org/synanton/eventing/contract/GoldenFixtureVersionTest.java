package org.synanton.eventing.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins each released JSON fixture to its filename version and exact bytes.
 * New schema versions add a fixture and a new entry here; released fixtures stay unchanged.
 */
class GoldenFixtureVersionTest {

    private static final Pattern VERSIONED_FIXTURE = Pattern.compile("^(event|command)\\.v([1-9][0-9]*)\\.json$");

    private static final List<Fixture> FROZEN_FIXTURES = List.of(
            new Fixture(
                    "event.v1.json",
                    "event_version",
                    1,
                    "d19851353f5102e777b0caba000059e6228ca1acb485bded10d534f391fd05d1"),
            new Fixture(
                    "command.v1.json",
                    "command_version",
                    1,
                    "37e9c86105aa5bf1f26aa411be03b5feec03ced6f485eab4e698a3c1d06c7beb"));

    private final ObjectMapper mapper = EventingJson.newMapper();

    @Test
    void fixturesAreVersionedInventoriedAndBytePinned() throws IOException, URISyntaxException {
        assertThat(goldenFileNames())
                .containsExactlyInAnyOrderElementsOf(
                        FROZEN_FIXTURES.stream().map(Fixture::fileName).collect(Collectors.toSet()));

        for (Fixture fixture : FROZEN_FIXTURES) {
            Matcher matcher = VERSIONED_FIXTURE.matcher(fixture.fileName());
            assertThat(matcher.matches()).as("fixture name %s carries a schema version", fixture.fileName())
                    .isTrue();
            assertThat(matcher.group(1)).isEqualTo(fixture.messageKind());
            assertThat(Integer.parseInt(matcher.group(2))).isEqualTo(fixture.version());

            byte[] bytes = fixtureBytes(fixture.fileName());
            JsonNode json = mapper.readTree(bytes);
            assertThat(json.path(fixture.versionField()).asInt(-1)).isEqualTo(fixture.version());
            assertThat(sha256(bytes)).isEqualTo(fixture.sha256());
        }
    }

    private static Set<String> goldenFileNames() throws IOException, URISyntaxException {
        var directory = Objects.requireNonNull(
                GoldenFixtureVersionTest.class.getResource("/golden"), "golden fixture directory");
        try (Stream<Path> paths = Files.list(Path.of(directory.toURI()))) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .collect(Collectors.toSet());
        }
    }

    private static byte[] fixtureBytes(String fileName) throws IOException {
        try (InputStream input = GoldenFixtureVersionTest.class.getResourceAsStream("/golden/" + fileName)) {
            if (input == null) {
                throw new IOException("Missing golden fixture " + fileName);
            }
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record Fixture(String fileName, String versionField, int version, String sha256) {

        private String messageKind() {
            return fileName.startsWith("event.") ? "event" : "command";
        }
    }
}
