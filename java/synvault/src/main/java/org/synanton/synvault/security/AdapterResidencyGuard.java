package org.synanton.synvault.security;

import java.io.Serial;
import java.util.List;
import java.util.Set;

public class AdapterResidencyGuard {

    public record Adapter(String name, String region) {}

    public Adapter select(List<Adapter> adapters, Set<String> allowedRegions) {
        List<Adapter> compatible = adapters.stream()
                .filter(adapter -> allowedRegions == null || allowedRegions.isEmpty()
                        || allowedRegions.contains(adapter.region()))
                .toList();
        if (compatible.isEmpty()) {
            throw new NoCompatibleAdapterException(allowedRegions);
        }
        return compatible.getFirst();
    }

    public static class NoCompatibleAdapterException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
        public NoCompatibleAdapterException(Set<String> allowedRegions) {
            super("no_compatible_adapter_for_residency " + allowedRegions);
        }
    }
}
