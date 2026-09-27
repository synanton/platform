package org.synanton.storage.contract;

import java.util.List;
import java.util.Objects;

/**
 * Startup failure: the selected provider cannot satisfy the deployment's required
 * contract. The message always names the provider, the missing capability, and the
 * requirement — operators must know <em>why</em> startup failed, especially when the
 * same adapter works in a different deployment mode (metadata-only vs revision).
 *
 * <p>{@link Provisional} {@code 1.32}: error shape binds to the 1.32 Operation/error
 * contract at freeze (tracked in {@code 011-provisional-followup.md}).
 */
@Provisional(value = "1.32", reason = "Startup-error shape binds to the 1.32 Operation/error contract at freeze")
public class ProviderIncompatibleException extends IllegalStateException {

    private final String providerName;
    private final List<String> missingCapabilities;

    public ProviderIncompatibleException(String providerName, List<String> missingCapabilities, String detail) {
        super("provider '"
                + providerName
                + "' incompatible: missing "
                + missingCapabilities
                + ". "
                + detail);
        this.providerName = Objects.requireNonNull(providerName, "providerName");
        this.missingCapabilities =
                List.copyOf(Objects.requireNonNull(missingCapabilities, "missingCapabilities"));
    }

    public String providerName() {
        return providerName;
    }

    public List<String> missingCapabilities() {
        return missingCapabilities;
    }
}
