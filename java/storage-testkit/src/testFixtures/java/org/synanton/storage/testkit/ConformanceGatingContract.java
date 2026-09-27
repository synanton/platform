package org.synanton.storage.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.contract.ConformanceEntry;
import org.synanton.storage.contract.ConformanceMatrix;
import org.synanton.storage.contract.ConformanceStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executable §9.3 conformance gating (YDB-POC-020): capability claims without
 * conformance evidence cannot stand. Concrete adapters subclass once per port side,
 * mapping their boolean flags to capability names.
 */
public abstract class ConformanceGatingContract {

    /** Adapter under test (fresh instance not required; matrices are static). */
    protected abstract Conformant adapter();

    /**
     * Claimed capability flags mapped to capability names, e.g.
     * {@code stores: capabilities().supportsTransactions() -> synvault.revision}.
     */
    protected abstract Map<String, Boolean> claimedFlags();

    @Test
    void claimedCapabilitiesHaveSupportedEvidence() {
        ConformanceMatrix matrix = adapter().conformance();
        List<String> violations = new ArrayList<>();
        claimedFlags()
                .forEach(
                        (capability, claimed) -> {
                            var entry = matrix.entry(capability);
                            if (claimed && !satisfiesClaim(entry)) {
                                violations.add(
                                        capability + ": claimed but neither SUPPORTED nor scoped-PARTIAL in matrix");
                            }
                            if (!claimed
                                    && entry.isPresent()
                                    && (entry.get().status() == ConformanceStatus.SUPPORTED
                                            || entry.get().status() == ConformanceStatus.PARTIAL)) {
                                violations.add(capability + ": evidenced in matrix but flag is false");
                            }
                        });
        assertThat(violations).as("flag/matrix mismatch").isEmpty();
    }

    private static boolean satisfiesClaim(java.util.Optional<ConformanceEntry> entry) {
        if (entry.isEmpty()) {
            return false;
        }
        return switch (entry.get().status()) {
            case SUPPORTED -> true;
            case PARTIAL -> entry.get().evidence().startsWith("scope=");
            default -> false;
        };
    }

    @Test
    void supportedEntriesNameLoadableEvidence() {
        ConformanceMatrix matrix = adapter().conformance();
        List<String> violations = new ArrayList<>();
        for (ConformanceEntry entry : matrix.entries()) {
            if (entry.status() == ConformanceStatus.SUPPORTED
                    || entry.status() == ConformanceStatus.PARTIAL) {
                String evidence = entry.evidence();
                if (entry.status() == ConformanceStatus.PARTIAL) {
                    assertThat(evidence)
                            .as("PARTIAL scope for " + entry.capability())
                            .startsWith("scope=");
                    int at = evidence.indexOf("test=");
                    evidence = at >= 0 ? evidence.substring(at + 5).trim() : "";
                }
                try {
                    Class.forName(evidence);
                } catch (ClassNotFoundException | LinkageError e) {
                    violations.add(entry.capability() + ": evidence class not loadable: " + evidence);
                }
            }
        }
        assertThat(violations).as("unsupported evidence").isEmpty();
    }

    @Test
    void unsupportedEntriesCarryReasons() {
        ConformanceMatrix matrix = adapter().conformance();
        for (ConformanceEntry entry : matrix.entries()) {
            if (entry.status() == ConformanceStatus.UNSUPPORTED) {
                assertThat(entry.evidence())
                        .as("reason for " + entry.capability())
                        .isNotBlank();
            }
        }
    }
}
